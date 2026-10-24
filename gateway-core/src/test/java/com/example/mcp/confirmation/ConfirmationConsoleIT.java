package com.example.mcp.confirmation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 确认台列表端点（GET /confirmations?status=）与静态页面壳：
 * confirmer 可查、user-key 被隔离、非法状态 400；页面壳公开但仅是 UI。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConfirmationConsoleIT {

    @LocalServerPort
    int port;

    RestClient client;

    @Autowired
    ConfirmationService confirmationService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private Map<String, Object> get(String path, String apiKey) {
        ResponseEntity<Map> resp = client.get()
                .uri(path)
                .header("X-API-Key", apiKey)
                .retrieve()
                .toEntity(Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    @Test
    void listEndpointReturnsPendingForConfirmer() {
        String marker = UUID.randomUUID().toString();
        // 用标记性的 callerId 定位本测试创建的确认单，不依赖共享库里的其他数据
        PendingExecution pending = confirmationService.createPending("console_probe",
                new com.example.mcp.security.CallerContext("console-" + marker, "tenant-a",
                        java.util.Set.of("user")), "{\"k\":\"console\"}");

        ResponseEntity<String> raw = client.get()
                .uri("/confirmations?status=PENDING")
                .header("X-API-Key", "admin-key")
                .retrieve()
                .toEntity(String.class);
        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(raw.getBody()).contains(pending.getConfirmationId().toString());
        assertThat(raw.getBody()).contains("console-" + marker);
        assertThat(raw.getBody()).contains("console_probe");
    }

    @Test
    void multiStatusQuerySpansRequestedStatesOnly() {
        // 页面"全部"下拉依赖逗号分隔多状态：两条不同状态的单都要出现
        PendingExecution pending = confirmationService.createPending("console_multi_probe",
                new com.example.mcp.security.CallerContext("console-multi", "tenant-a",
                        java.util.Set.of("user")), "{\"k\":\"m\"}");
        confirmationService.confirm(pending.getConfirmationId());

        ResponseEntity<String> raw = client.get()
                .uri("/confirmations?status=PENDING,CONFIRMED")
                .header("X-API-Key", "admin-key")
                .retrieve()
                .toEntity(String.class);
        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> rows = com.example.mcp.support.JsonMappers.MAPPER.readValue(
                raw.getBody(), new tools.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
                });
        // 本测试的确认单（CONFIRMED 态）在结果中
        assertThat(rows).anySatisfy(row ->
                assertThat(row.get("confirmationId")).isEqualTo(pending.getConfirmationId().toString()));
        // 每一行的状态都必须属于请求的状态集合（不请求 EXECUTED/REJECTED/EXPIRED 就不该出现）
        assertThat(rows).allSatisfy(row ->
                assertThat((String) row.get("status")).isIn("PENDING", "CONFIRMED"));
    }

    @Test
    void listEndpointForbiddenForUserRole() {
        // user-key 不含 confirmer 角色：确认台数据对 AI 客户端凭据不可见
        assertThat(catchThrowableOfType(RestClientResponseException.class,
                () -> client.get()
                        .uri("/confirmations?status=PENDING")
                        .header("X-API-Key", "user-key")
                        .retrieve()
                        .toEntity(String.class))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void invalidStatusNameReturns400() {
        assertThat(catchThrowableOfType(RestClientResponseException.class,
                () -> client.get()
                        .uri("/confirmations?status=NOT_A_STATE")
                        .header("X-API-Key", "admin-key")
                        .retrieve()
                        .toEntity(String.class))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void openApiRendersOperationSummaries() {
        // @Operation 注解必须出现在 OpenAPI 文档里（swagger 是外部使用者了解 REST 的第一界面）
        String docs = client.get().uri("/v3/api-docs")
                .retrieve().toEntity(String.class).getBody();
        assertThat(docs).contains("确认单列表");
        assertThat(docs).contains("确认单据");
        assertThat(docs).contains("重驱失败消息");
        assertThat(docs).contains("发放新 Key");
        assertThat(docs).contains("审计查询");
    }

    @Test
    void emptyStatusCollectionReturns400() {
        // status=,（逗号分隔后全空）：既不是默认值也非合法状态，必须显式 400 而非回落 PENDING
        assertThat(catchThrowableOfType(RestClientResponseException.class,
                () -> client.get()
                        .uri("/confirmations?status=,")
                        .header("X-API-Key", "admin-key")
                        .retrieve()
                        .toEntity(String.class))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void consoleShellIsPublicButDataIsNot() {
        // 页面壳公开（浏览器导航带不了认证头）
        assertThat(client.get().uri("/confirmation-console.html")
                .retrieve().toEntity(String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        // 数据端点仍然保护
        assertThat(catchThrowableOfType(RestClientResponseException.class,
                () -> client.get().uri("/confirmations")
                        .retrieve().toEntity(String.class))
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
