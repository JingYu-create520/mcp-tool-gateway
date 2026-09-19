package com.example.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /mcp 端点认证集成测试：认证开启后 MCP 协议握手必须仍能正常工作。
 * 无 Key → 401；user-key → MCP 全流程可用但 /admin 403；admin-key → /admin 200；
 * 工具级 requiredRole 不足 → isError=true 且文本含 permission_denied。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class McpEndpointAuthIT {

    private static final String INIT_BODY =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\","
                    + "\"capabilities\":{},\"clientInfo\":{\"name\":\"it\",\"version\":\"0.0.1\"}}}";

    @LocalServerPort
    int port;

    RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void initializeWithoutApiKeyReturns401() {
        assertThat(postMcp(INIT_BODY, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void initializeWithUserKeySucceedsAndMcpFlowWorks() {
        ResponseEntity<String> init = postMcp(INIT_BODY, "user-key", null);
        assertThat(init.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(init.getBody()).contains("protocolVersion");

        String sessionId = init.getHeaders().getFirst("Mcp-Session-Id");
        assertThat(sessionId).isNotBlank();

        ResponseEntity<String> echoCall = postMcp(
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
    void adminApiOkForAdminRole() {
        assertThat(getAdmin("/admin/tools", "admin-key").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void toolCallDeniedForMissingRequiredRole() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> create = Map.of(
                "name", "restricted_tool",
                "description", "仅 admin 可调用的探针",
                "inputSchema", "{\"type\":\"object\",\"properties\":{\"k\":{\"type\":\"string\"}},\"required\":[\"k\"]}",
                "sideEffect", "READ",
                "requiredRole", "admin",
                "enabled", true);
        ResponseEntity<String> created = client.post().uri("/admin/tools")
                .header("X-API-Key", "admin-key")
                .contentType(MediaType.APPLICATION_JSON)
                .body(create)
                .retrieve()
                .toEntity(String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        try {
            ResponseEntity<String> init = postMcp(INIT_BODY, "user-key", null);
            String sessionId = init.getHeaders().getFirst("Mcp-Session-Id");
            ResponseEntity<String> denied = postMcp(
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
