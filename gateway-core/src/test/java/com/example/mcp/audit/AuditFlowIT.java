package com.example.mcp.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计端到端：echo → EXECUTED；WRITE 工具 → PENDING/CONFIRMED/EXECUTED/REJECTED；
 * user 越权 → DENIED。全部脱敏后的完整轨迹落 audit_log。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class AuditFlowIT {

    private static final String INIT_BODY =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\","
                    + "\"capabilities\":{},\"clientInfo\":{\"name\":\"it\",\"version\":\"0.0.1\"}}}";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

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
    void fullAuditTrail() {
        registerWriteTool("audit_probe");
        try {
            String sidUser = init("user-key");
            String sidAdmin = init("admin-key");

            // 1. echo（user-key）→ EXECUTED，带 durationMs
            postMcp(callTool("echo", "{\"message\":\"audit-flow\"}"), "user-key", sidUser);
            awaitAudit("echo", "EXECUTED", event -> "user-1".equals(event.getCallerId())
                    && event.getDurationMs() != null);

            // 2. WRITE 工具首次调用 → PENDING（带 confirmationId）
            String input = "{\"password\":\"flow-secret\",\"k\":\"a\"}";
            Map<String, Object> pending = outcomeOf(
                    postMcp(callTool("audit_probe", input), "admin-key", sidAdmin).getBody());
            assertThat(pending).containsEntry("status", "pending");
            String confirmationId = (String) pending.get("confirmationId");
            awaitAudit("audit_probe", "PENDING", event -> confirmationId.equals(event.getConfirmationId()));

            // 3. confirm → CONFIRMED
            client.post().uri("/confirmations/{id}/confirm", confirmationId)
                    .header("X-API-Key", "admin-key").retrieve().toBodilessEntity();
            awaitAudit("audit_probe", "CONFIRMED", event -> confirmationId.equals(event.getConfirmationId()));

            // 4. 再次调用 → EXECUTED
            postMcp(callTool("audit_probe", input), "admin-key", sidAdmin);
            awaitAudit("audit_probe", "EXECUTED", event -> confirmationId.equals(event.getConfirmationId()));

            // 5. 拒绝 → REJECTED
            Map<String, Object> second = outcomeOf(
                    postMcp(callTool("audit_probe", "{\"k\":\"b\"}"), "admin-key", sidAdmin).getBody());
            String rejectedId = (String) second.get("confirmationId");
            client.post().uri("/confirmations/{id}/reject", rejectedId)
                    .header("X-API-Key", "admin-key").retrieve().toBodilessEntity();
            awaitAudit("audit_probe", "REJECTED", event -> rejectedId.equals(event.getConfirmationId()));

            // 6. user 越权调用 admin 工具 → DENIED
            postMcp(callTool("audit_probe", "{\"k\":\"x\"}"), "user-key", sidUser);
            awaitAudit("audit_probe", "DENIED", event -> "user-1".equals(event.getCallerId()));

            // 脱敏验证：EXECUTED 轨迹的 input 中 password 已打码
            List<AuditEvent> executed = auditRepository.findByToolNameAndStatusOrderByCreatedAtDesc(
                    "audit_probe", "PENDING");
            assertThat(executed).anyMatch(event ->
                    event.getInput() != null && event.getInput().contains("\"password\":\"***\""));
        } finally {
            deleteTool("audit_probe");
        }
    }

    private void registerWriteTool(String name) {
        Map<String, Object> create = Map.of(
                "name", name,
                "description", "阶段四审计探针",
                "inputSchema", "{\"type\":\"object\",\"properties\":{\"k\":{\"type\":\"string\"}},\"required\":[\"k\"]}",
                "sideEffect", "WRITE",
                "requiredRole", "admin",
                "enabled", true);
        ResponseEntity<String> created = client.post().uri("/admin/tools")
                .header("X-API-Key", "admin-key")
                .contentType(MediaType.APPLICATION_JSON)
                .body(create)
                .retrieve()
                .toEntity(String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private void deleteTool(String name) {
        client.delete().uri("/admin/tools/{name}", name)
                .header("X-API-Key", "admin-key")
                .retrieve()
                .toBodilessEntity();
    }

    private String init(String apiKey) {
        ResponseEntity<String> init = postMcp(INIT_BODY, apiKey, null);
        assertThat(init.getStatusCode()).isEqualTo(HttpStatus.OK);
        String sessionId = init.getHeaders().getFirst("Mcp-Session-Id");
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

    private void awaitAudit(String toolName, String status, java.util.function.Predicate<AuditEvent> extra) {
        for (int i = 0; i < 30; i++) {
            relay.relayOnce();
            boolean found = auditRepository.findByToolNameAndStatusOrderByCreatedAtDesc(toolName, status)
                    .stream().anyMatch(extra);
            if (found) {
                return;
            }
        }
        throw new AssertionError("审计记录未出现: " + toolName + "/" + status);
    }
}
