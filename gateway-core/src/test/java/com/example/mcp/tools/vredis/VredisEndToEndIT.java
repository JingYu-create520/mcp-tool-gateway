package com.example.mcp.tools.vredis;

import com.example.mcp.audit.AuditEvent;
import com.example.mcp.audit.AuditEventRepository;
import com.example.mcp.audit.OutboxRelay;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * vredis 工具端到端：权限矩阵（user 只读、admin 写、user 越权 DENIED）、
 * WRITE/DANGEROUS 人工确认全流程、审计轨迹与脱敏，三者联动。
 * 注：MCP 响应是 SSE，按阶段四方式解析 data: 行再取 content[0].text。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class VredisEndToEndIT {

    private static final String INIT_BODY =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\","
                    + "\"capabilities\":{},\"clientInfo\":{\"name\":\"it\",\"version\":\"0.0.1\"}}}";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    static final MockVredisServer vredis = new MockVredisServer();

    @BeforeAll
    static void startMock() {
        vredis.start();
    }

    @AfterAll
    static void stopMock() {
        vredis.stop();
    }

    @DynamicPropertySource
    static void vredisProps(DynamicPropertyRegistry registry) {
        registry.add("vredis.base-url", vredis::baseUrl);
    }

    @LocalServerPort
    int port;

    RestClient client;

    @Autowired
    OutboxRelay relay;

    @Autowired
    AuditEventRepository auditRepository;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void fullVredisLifecycle() {
        // a. user 调 vredis_search（READ，requiredRole=user）→ 直通执行
        String sidUser = init("user-key");
        Map<String, Object> search = outcomeOf(
                postMcp(callTool("vredis_search", "{\"pattern\":\"user:*\"}"), "user-key", sidUser).getBody());
        assertThat(search).containsEntry("status", "executed");
        assertThat((String) search.get("result")).contains("\"results\":[]");

        // b. user 调 vredis_stats（READ）→ 直通执行
        Map<String, Object> stats = outcomeOf(
                postMcp(callTool("vredis_stats", "{}"), "user-key", sidUser).getBody());
        assertThat(stats).containsEntry("status", "executed");
        assertThat((String) stats.get("result")).contains("\"count\":0");

        // c. user 调 vredis_upsert（WRITE，requiredRole=admin）→ 权限拒绝
        String upsertInput = "{\"key\":\"k1\",\"value\":\"v1\",\"password\":\"topsecret\"}";
        ResponseEntity<String> denied = postMcp(callTool("vredis_upsert", upsertInput), "user-key", sidUser);
        assertThat(denied.getBody()).contains("permission_denied");

        // d. admin 调 vredis_upsert → pending
        String sidAdmin = init("admin-key");
        Map<String, Object> pending = outcomeOf(
                postMcp(callTool("vredis_upsert", upsertInput), "admin-key", sidAdmin).getBody());
        assertThat(pending).containsEntry("status", "pending");
        String confirmationId = (String) pending.get("confirmationId");

        // e. REST confirm → 再次同输入调用 → 执行（result 来自 MockVredisServer）
        assertThat(client.post().uri("/confirmations/{id}/confirm", confirmationId)
                .header("X-API-Key", "admin-key")
                .retrieve().toEntity(String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> executed = outcomeOf(
                postMcp(callTool("vredis_upsert", upsertInput), "admin-key", sidAdmin).getBody());
        assertThat(executed).containsEntry("status", "executed");
        assertThat((String) executed.get("result")).contains("mock-id");
        assertThat(executed).containsEntry("confirmationId", confirmationId);

        // f. admin 调 vredis_delete（DANGEROUS）→ pending
        Map<String, Object> deletePending = outcomeOf(
                postMcp(callTool("vredis_delete", "{\"key\":\"k1\"}"), "admin-key", sidAdmin).getBody());
        assertThat(deletePending).containsEntry("status", "pending");
        String deleteId = (String) deletePending.get("confirmationId");

        // g. reject → REJECTED
        assertThat(client.post().uri("/confirmations/{id}/reject", deleteId)
                .header("X-API-Key", "admin-key")
                .retrieve().toEntity(String.class).getBody()).contains("REJECTED");

        // h. 拒绝后再调用 → 新的 pending（旧的保持 REJECTED）
        Map<String, Object> repending = outcomeOf(
                postMcp(callTool("vredis_delete", "{\"key\":\"k1\"}"), "admin-key", sidAdmin).getBody());
        assertThat(repending).containsEntry("status", "pending");
        assertThat((String) repending.get("confirmationId")).isNotEqualTo(deleteId);
        assertThat((String) repending.get("confirmationId")).isNotEqualTo(confirmationId);

        // i. 审计轨迹：vredis_upsert 有 PENDING / CONFIRMED / EXECUTED 三条
        awaitAudit("vredis_upsert", "PENDING");
        awaitAudit("vredis_upsert", "CONFIRMED");
        awaitAudit("vredis_upsert", "EXECUTED");
        // j. 脱敏：upsert 输入中的 password 在审计里已打码
        List<AuditEvent> pendingRows = auditRepository
                .findByToolNameAndStatusOrderByCreatedAtDesc("vredis_upsert", "PENDING");
        assertThat(pendingRows).anySatisfy(row -> {
            assertThat(row.getConfirmationId()).isNotNull();
            assertThat(row.getInput()).contains("\"password\":\"***\"").doesNotContain("topsecret");
        });
    }

    private String init(String apiKey) {
        ResponseEntity<String> init = postMcp(INIT_BODY, apiKey, null);
        assertThat(init.getStatusCode()).isEqualTo(HttpStatus.OK);
        String sessionId = init.getHeaders().getFirst("Mcp-Session-Id");
        assertThat(sessionId).isNotBlank();
        postMcp("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", apiKey, sessionId);
        return sessionId;
    }

    private String callTool(String name, String argsJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name
                + "\",\"arguments\":" + argsJson + "}}";
    }

    private String extractText(String body) {
        String data = body.lines()
                .filter(line -> line.startsWith("data:"))
                .findFirst()
                .map(line -> line.substring("data:".length()))
                .orElse(body);
        return JSON.readTree(data).at("/result/content/0/text").asString();
    }

    private Map<String, Object> outcomeOf(String body) {
        return JSON.readValue(extractText(body), MAP_TYPE);
    }

    private ResponseEntity<String> postMcp(String body, String apiKey, String sessionId) {
        try {
            var request = client.post().uri("/mcp")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM);
            if (apiKey != null) {
                request.header("X-API-Key", apiKey);
            }
            if (sessionId != null) {
                request.header("Mcp-Session-Id", sessionId);
            }
            return request.body(body).retrieve().toEntity(String.class);
        } catch (RestClientResponseException e) {
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        }
    }

    private void awaitAudit(String toolName, String status) {
        for (int i = 0; i < 30; i++) {
            relay.relayOnce();
            boolean found = !auditRepository
                    .findByToolNameAndStatusOrderByCreatedAtDesc(toolName, status).isEmpty();
            if (found) {
                return;
            }
        }
        throw new AssertionError("审计记录未出现: " + toolName + "/" + status);
    }
}
