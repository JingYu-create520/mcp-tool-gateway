package com.example.mcp;

import com.example.mcp.support.McpTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.UUID;

import static com.example.mcp.support.McpTestSupport.INIT_BODY;
import static com.example.mcp.support.McpTestSupport.init;
import static com.example.mcp.support.McpTestSupport.postMcp;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * /mcp 端点认证集成测试：认证开启后 MCP 协议握手必须仍能正常工作。
 * 无 Key → 401；user-key → MCP 全流程可用但 /admin 403；admin-key → /admin 200；
 * 工具级 requiredRole 不足 → isError=true 且文本含 permission_denied。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class McpEndpointAuthIT {

    @LocalServerPort
    int port;

    RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void initializeWithoutApiKeyReturns401() {
        assertThat(postMcp(client, INIT_BODY, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unknownApiKeyReturns401() {
        // 与"缺头"不同的代码路径：过滤器查库无匹配 → 不写 SecurityContext → 401
        assertThat(postMcp(client, INIT_BODY, "garbage-key", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void initializeWithUserKeySucceedsAndMcpFlowWorks() {
        ResponseEntity<String> init = postMcp(client, INIT_BODY, "user-key", null);
        assertThat(init.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(init.getBody()).contains("protocolVersion");

        String sessionId = init.getHeaders().getFirst("Mcp-Session-Id");
        assertThat(sessionId).isNotBlank();

        ResponseEntity<String> echoCall = postMcp(client,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"echo\",\"arguments\":{\"message\":\"auth-ok\"}}}",
                "user-key", sessionId);
        assertThat(echoCall.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(echoCall.getBody()).contains("auth-ok");
    }

    @Test
    void adminApiForbiddenForUserRole() {
        assertThat(getAdmin("/admin/tools", "user-key").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void confirmationsForbiddenForUserRole() {
        // 确认台凭据隔离：user-key 不含 confirmer 角色 → 403（AI 凭据不能碰确认接口）；
        // admin-key 含 confirmer → 权限层放行（随机 id 得 404 而非 403）
        assertThat(getAdmin("/confirmations/" + UUID.randomUUID(), "user-key").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(getAdmin("/confirmations/" + UUID.randomUUID(), "admin-key").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void adminApiOkForAdminRole() {
        assertThat(getAdmin("/admin/tools", "admin-key").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void toolCallDeniedForMissingRequiredRole() {
        Map<String, Object> create = Map.of(
                "name", "restricted_tool",
                "description", "仅 admin 可调用的探针",
                "inputSchema", "{\"type\":\"object\",\"properties\":{\"k\":{\"type\":\"string\"}},\"required\":[\"k\"]}",
                "sideEffect", "READ",
                "requiredRole", "admin",
                "enabled", true);
        ResponseEntity<String> created = client.post().uri("/admin/tools")
                .header("X-API-Key", "admin-key")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(create)
                .retrieve()
                .toEntity(String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        try {
            String sessionId = McpTestSupport.init(client, "user-key");
            ResponseEntity<String> denied = postMcp(client,
                    "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                            + "\"params\":{\"name\":\"restricted_tool\",\"arguments\":{\"k\":\"x\"}}}",
                    "user-key", sessionId);
            assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(denied.getBody()).contains("\"isError\":true").contains("permission_denied");
        } finally {
            client.delete().uri("/admin/tools/restricted_tool")
                    .header("X-API-Key", "admin-key")
                    .retrieve()
                    .toBodilessEntity();
        }
    }

    private ResponseEntity<String> getAdmin(String path, String apiKey) {
        try {
            return client.get().uri(path)
                    .header("X-API-Key", apiKey)
                    .retrieve()
                    .toEntity(String.class);
        } catch (RestClientResponseException e) {
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        }
    }
}
