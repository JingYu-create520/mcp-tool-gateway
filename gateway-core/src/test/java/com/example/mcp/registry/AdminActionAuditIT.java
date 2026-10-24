package com.example.mcp.registry;

import com.example.mcp.audit.AuditEventRepository;
import com.example.mcp.audit.OutboxRelay;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端写操作的审计：注册/更新/删除改变 AI 可用的能力面，
 * 必须留痕（TOOL_REGISTERED / TOOL_UPDATED / TOOL_DELETED）——
 * 此前审计只覆盖工具调用，"谁在何时删了工具"不可追责。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminActionAuditIT {

    @DynamicPropertySource
    static void isolatedDb(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:admin_audit_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    }

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
    void toolDirectoryWritesLeaveAuditTrail() {
        String tool = "audit_probe_" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> body = Map.of(
                "name", tool,
                "description", "管理审计探针",
                "inputSchema", "{\"type\":\"object\"}",
                "sideEffect", "READ",
                "enabled", true);

        // 注册 → TOOL_REGISTERED
        client.post().uri("/admin/tools").header("X-API-Key", "admin-key")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toBodilessEntity();

        // 更新 → TOOL_UPDATED
        Map<String, Object> updateBody = new LinkedHashMap<>(body);
        updateBody.put("description", "管理审计探针-改");
        client.put().uri("/admin/tools/{name}", tool).header("X-API-Key", "admin-key")
                .contentType(MediaType.APPLICATION_JSON)
                .body(updateBody)
                .retrieve().toBodilessEntity();

        // 删除 → TOOL_DELETED
        client.delete().uri("/admin/tools/{name}", tool).header("X-API-Key", "admin-key")
                .retrieve().toBodilessEntity();

        // 投递后三条审计齐全，操作者是 admin-user
        for (int i = 0; i < 20; i++) {
            relay.relayOnce();
            if (count(tool, "TOOL_REGISTERED") > 0 && count(tool, "TOOL_UPDATED") > 0
                    && count(tool, "TOOL_DELETED") > 0) {
                break;
            }
        }
        assertThat(count(tool, "TOOL_REGISTERED")).isEqualTo(1);
        assertThat(count(tool, "TOOL_UPDATED")).isEqualTo(1);
        assertThat(count(tool, "TOOL_DELETED")).isEqualTo(1);
        assertThat(auditRepository.findByToolNameAndStatusOrderByCreatedAtDesc(tool, "TOOL_DELETED"))
                .allSatisfy(event -> {
                    assertThat(event.getCallerId()).isEqualTo("admin-user");
                    assertThat(event.getTenantId()).isEqualTo("tenant-a");
                });
    }

    private long count(String tool, String status) {
        return auditRepository.findByToolNameAndStatusOrderByCreatedAtDesc(tool, status).size();
    }
}
