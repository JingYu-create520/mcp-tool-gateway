package com.example.mcp.audit;

import com.example.mcp.McpGatewayApplication;
import com.example.mcp.support.McpTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

import static com.example.mcp.support.McpTestSupport.callToolBody;
import static com.example.mcp.support.McpTestSupport.init;
import static com.example.mcp.support.McpTestSupport.postMcp;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计端到端：echo → EXECUTED；WRITE 工具 → PENDING/CONFIRMED/EXECUTED/REJECTED；
 * user 越权 → DENIED。全部脱敏后的完整轨迹落 audit_log。
 */
@SpringBootTest(classes = McpGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class AuditFlowIT {

    @LocalServerPort
    int port;

    RestClient client;

    @Autowired
    OutboxRelay relay;

    @Autowired
    AuditEventRepository auditRepository;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void fullAuditTrail() {
        registerWriteTool("audit_probe");
        try {
            String sidUser = init(client, "user-key");
            String sidAdmin = init(client, "admin-key");

            // 1. echo（user-key）→ EXECUTED，带 durationMs；消息埋唯一串防 rerun 撞历史轨迹
            String echoMarker = "audit-flow-" + UUID.randomUUID();
            postMcp(client, callToolBody("echo", "{\"message\":\"" + echoMarker + "\"}"), "user-key", sidUser);
            awaitAudit("echo", "EXECUTED", event -> "user-1".equals(event.getCallerId())
                    && event.getDurationMs() != null
                    && String.valueOf(event.getInput()).contains(echoMarker));

            // 2. WRITE 工具首次调用 → PENDING（带 confirmationId）
            Map<String, Object> pending = McpTestSupport.outcomeOf(
                    postMcp(client, callToolBody("audit_probe", "{\"password\":\"flow-secret\",\"k\":\"a\"}"),
                            "admin-key", sidAdmin).getBody());
            assertThat(pending).containsEntry("status", "pending");
            String confirmationId = (String) pending.get("confirmationId");
            awaitAudit("audit_probe", "PENDING", event -> confirmationId.equals(event.getConfirmationId()));

            // 3. confirm → CONFIRMED
            client.post().uri("/confirmations/{id}/confirm", confirmationId)
                    .header("X-API-Key", "admin-key").retrieve().toBodilessEntity();
            awaitAudit("audit_probe", "CONFIRMED", event -> confirmationId.equals(event.getConfirmationId()));

            // 4. 再次调用 → EXECUTED
            postMcp(client, callToolBody("audit_probe", "{\"password\":\"flow-secret\",\"k\":\"a\"}"),
                    "admin-key", sidAdmin);
            awaitAudit("audit_probe", "EXECUTED", event -> confirmationId.equals(event.getConfirmationId()));

            // 5. 拒绝 → REJECTED
            Map<String, Object> second = McpTestSupport.outcomeOf(
                    postMcp(client, callToolBody("audit_probe", "{\"k\":\"b\"}"), "admin-key", sidAdmin).getBody());
            String rejectedId = (String) second.get("confirmationId");
            client.post().uri("/confirmations/{id}/reject", rejectedId)
                    .header("X-API-Key", "admin-key").retrieve().toBodilessEntity();
            awaitAudit("audit_probe", "REJECTED", event -> rejectedId.equals(event.getConfirmationId()));

            // 6. user 越权调用 admin 工具 → DENIED
            postMcp(client, callToolBody("audit_probe", "{\"k\":\"x\"}"), "user-key", sidUser);
            awaitAudit("audit_probe", "DENIED", event -> "user-1".equals(event.getCallerId()));

            // 脱敏验证：PENDING 轨迹的 input 中 password 已打码
            assertThat(auditRepository.findByToolNameAndStatusOrderByCreatedAtDesc("audit_probe", "PENDING"))
                    .anySatisfy(event -> assertThat(event.getInput())
                            .contains("\"password\":\"***\"").doesNotContain("flow-secret"));
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
