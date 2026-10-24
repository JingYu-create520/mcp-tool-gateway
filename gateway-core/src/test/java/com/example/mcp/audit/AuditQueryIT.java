package com.example.mcp.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.UUID;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计查询 API：callerId / toolName / status / 时间范围 / 分页。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class AuditQueryIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @LocalServerPort
    int port;

    RestClient client;

    @Autowired
    AuditEventPublisher publisher;

    @Autowired
    OutboxRelay relay;

    @BeforeEach
    void setUp() {
        client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-API-Key", "admin-key")
                .build();
    }

    @Test
    void queryFiltersAndPagination() {
        Instant now = Instant.now();
        seed("query_probe", "user-1", "EXECUTED", now.minus(Duration.ofHours(2)));
        seed("query_probe", "user-2", "DENIED", now.minus(Duration.ofHours(1)));
        seed("query_probe", "user-1", "REJECTED", now);
        awaitSeeded();

        Map<String, Object> all = query(Map.of("toolName", "query_probe"));
        assertThat(total(all)).isEqualTo(3);

        Map<String, Object> byCaller = query(Map.of("toolName", "query_probe", "callerId", "user-1"));
        assertThat(total(byCaller)).isEqualTo(2);
        assertThat(content(byCaller)).allSatisfy(event ->
                assertThat(event.get("callerId")).isEqualTo("user-1"));

        Map<String, Object> byStatus = query(Map.of("toolName", "query_probe", "status", "DENIED"));
        assertThat(total(byStatus)).isEqualTo(1);
        assertThat(content(byStatus).get(0).get("status")).isEqualTo("DENIED");

        String from = now.minus(Duration.ofMinutes(90)).toString();
        Map<String, Object> byTime = query(Map.of("toolName", "query_probe", "from", from));
        assertThat(total(byTime)).isEqualTo(2);

        Map<String, Object> page0 = query(Map.of("toolName", "query_probe", "size", "2", "page", "0"));
        assertThat(content(page0)).hasSize(2);
        assertThat(totalPages(page0)).isEqualTo(2);
    }

    @Test
    void confirmationIdFilterReturnsOnlyMatchingTrail() {
        String cid = UUID.randomUUID().toString();
        Instant now = Instant.now();
        seed("cid_probe", "user-1", "PENDING", now.minus(Duration.ofMinutes(3)), cid);
        seed("cid_probe", "user-1", "CONFIRMED", now.minus(Duration.ofMinutes(2)), cid);
        seed("cid_probe", "user-1", "EXECUTING", now.minus(Duration.ofMinutes(1)), cid);
        seed("cid_probe", "user-1", "EXECUTED", now, cid);
        seed("cid_probe", "user-2", "PENDING", now); // 同工具无 confirmationId，不应混入

        // 等待本测试自己的 4 条轨迹落库（不能复用 awaitSeeded——它锚定的是另一个测试的种子数）
        for (int i = 0; i < 30; i++) {
            relay.relayOnce();
            if (total(query(Map.of("toolName", "cid_probe", "confirmationId", cid))) == 4) {
                break;
            }
        }

        Map<String, Object> trail = query(Map.of("toolName", "cid_probe", "confirmationId", cid));
        assertThat(total(trail)).isEqualTo(4);
        assertThat(content(trail)).allSatisfy(event ->
                assertThat(event.get("confirmationId")).isEqualTo(cid));
    }

    @Test
    void negativePageParameterIs400Not500() {
        org.springframework.web.client.RestClientResponseException e =
                org.assertj.core.api.Assertions.catchThrowableOfType(
                        org.springframework.web.client.RestClientResponseException.class,
                        () -> client.get()
                                .uri((UriBuilder builder) -> {
                                    builder.path("/admin/audit");
                                    builder.queryParam("page", "-1");
                                    return builder.build();
                                })
                                .retrieve()
                                .toEntity(String.class));
        assertThat(e.getStatusCode().value()).isEqualTo(400);
    }

    private void seed(String toolName, String callerId, String status, Instant createdAt) {
        seed(toolName, callerId, status, createdAt, null);
    }

    private void seed(String toolName, String callerId, String status, Instant createdAt, String confirmationId) {
        AuditEvent event = new AuditEvent();
        event.setCallerId(callerId);
        event.setTenantId("tenant-a");
        event.setToolName(toolName);
        event.setInput("{\"k\":\"v\"}");
        event.setStatus(status);
        event.setCreatedAt(createdAt);
        event.setConfirmationId(confirmationId);
        publisher.publish(event);
    }

    private void awaitSeeded() {
        for (int i = 0; i < 30; i++) {
            relay.relayOnce();
            Map<String, Object> all = query(Map.of("toolName", "query_probe"));
            if (total(all) == 3) {
                return;
            }
        }
        throw new AssertionError("种子审计记录未全部就绪");
    }

    private Map<String, Object> query(Map<String, Object> params) {
        String body = client.get()
                .uri((UriBuilder builder) -> {
                    builder.path("/admin/audit");
                    params.forEach(builder::queryParam);
                    return builder.build();
                })
                .retrieve()
                .toEntity(String.class)
                .getBody();
        return JSON.readValue(body, MAP_TYPE);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> content(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("content");
    }

    private long total(Map<String, Object> page) {
        return ((Number) page.get("totalElements")).longValue();
    }

    private int totalPages(Map<String, Object> page) {
        return ((Number) page.get("totalPages")).intValue();
    }
}
