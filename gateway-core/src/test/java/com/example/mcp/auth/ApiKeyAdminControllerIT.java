package com.example.mcp.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.example.mcp.audit.AuditEventRepository;
import com.example.mcp.audit.OutboxRelay;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API Key 管理端点的完整生命周期：发放（明文一次性返回）→ 认证可用 → 列表无哈希 → 禁用后 401。
 * 这是 gateway.seed-api-keys=false 部署路径的发钥手段——此前只能手工 SQL。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApiKeyAdminControllerIT {

    @DynamicPropertySource
    static void isolatedDb(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:api_key_admin_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    }

    @LocalServerPort
    int port;

    RestClient client;

    @Autowired
    ApiKeyJpaRepository repository;

    @Autowired
    DbApiKeyStore store;

    @Autowired
    OutboxRelay relay;

    @Autowired
    AuditEventRepository auditRepository;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void issuedKeyAuthenticatesAndDisablesCleanly() {
        String caller = "issued-" + UUID.randomUUID().toString().substring(0, 8);

        // 发放：明文一次性返回
        Map<String, Object> created = client.post().uri("/admin/api-keys")
                .header("X-API-Key", "admin-key")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("callerId", caller, "tenantId", "tenant-b", "roles", "user"))
                .retrieve().body(Map.class);
        String plaintext = String.valueOf(created.get("apiKey"));
        assertThat(plaintext).startsWith("mcp_").hasSizeGreaterThan(40);

        // 认证路径可用（新 Key 能通过认证访问 /mcp）
        int authOk = client.post().uri("/mcp")
                .header("X-API-Key", plaintext)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Accept", "application/json, text/event-stream")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                        + "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}")
                .retrieve().toEntity(String.class).getStatusCode().value();
        assertThat(authOk).isEqualTo(200);

        // 列表含新 Key 但绝无哈希/明文
        String listBody = client.get().uri("/admin/api-keys")
                .header("X-API-Key", "admin-key")
                .retrieve().body(String.class);
        assertThat(listBody).contains(caller);
        assertThat(listBody).doesNotContain(plaintext);
        assertThat(listBody).doesNotContain(DbApiKeyStore.sha256(plaintext));

        // 禁用 → 认证立即 401
        client.delete().uri("/admin/api-keys/{caller}", caller)
                .header("X-API-Key", "admin-key")
                .retrieve().toBodilessEntity();
        assertThat(store.findByKey(plaintext)).isNull();
        // 禁用的行保留（防种子查重复活语义）
        List<ApiKeyEntity> rows = repository.findAll().stream()
                .filter(e -> caller.equals(e.getCallerId())).toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).isEnabled()).isFalse();

        // 发钥与禁钥均有审计留痕（KEY_ISSUED / KEY_DISABLED），且绝不包含明文
        for (int i = 0; i < 20; i++) {
            relay.relayOnce();
            if (!auditRepository.findByToolNameAndStatusOrderByCreatedAtDesc(caller, "KEY_ISSUED").isEmpty()
                    && !auditRepository.findByToolNameAndStatusOrderByCreatedAtDesc(caller, "KEY_DISABLED").isEmpty()) {
                break;
            }
        }
        var issued = auditRepository.findByToolNameAndStatusOrderByCreatedAtDesc(caller, "KEY_ISSUED");
        assertThat(issued).hasSize(1);
        assertThat(String.valueOf(issued.get(0).getInput())).contains("tenant-b").doesNotContain(plaintext);
        assertThat(auditRepository.findByToolNameAndStatusOrderByCreatedAtDesc(caller, "KEY_DISABLED")).hasSize(1);
        // 不存在的 callerId → 404
        int notFound = client.delete().uri("/admin/api-keys/{caller}", "no-such-caller")
                .header("X-API-Key", "admin-key")
                .exchange((req, resp) -> resp.getStatusCode().value());
        assertThat(notFound).isEqualTo(404);
    }
}
