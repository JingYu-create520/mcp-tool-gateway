package com.example.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段三端到端：注册 WRITE 工具 → MCP 调用得 pending → REST confirm →
 * get_confirmation_status 查询 CONFIRMED → 再次 MCP 调用触发执行 EXECUTED；拒绝场景不执行。
 * 注意：tools/call 响应是 SSE，内层 JSON 在 content[0].text 中，需先取 text 再反序列化。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class ConfirmationFlowIT {

    private static final String INIT_BODY =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\","
                    + "\"capabilities\":{},\"clientInfo\":{\"name\":\"it\",\"version\":\"0.0.1\"}}}";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @LocalServerPort
    int port;

    RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void writeToolExecutesOnlyAfterConfirmation() {
        String tool = "flow_tool_confirm";
        String input = "{\"k\":\"v\"}";
        registerWriteTool(tool);
        try {
            String sid = init("admin-key");

            Map<String, Object> first = outcomeOf(postMcp(callTool(tool, input), "admin-key", sid).getBody());
            assertThat(first).containsEntry("status", "pending");
            String confirmationId = (String) first.get("confirmationId");

            ResponseEntity<String> confirmed = postConfirmation(confirmationId, "confirm");
            assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(confirmed.getBody()).contains("CONFIRMED");

            String statusText = extractText(postMcp(callTool("get_confirmation_status",
                    "{\"confirmationId\":\"" + confirmationId + "\"}"), "admin-key", sid).getBody());
            assertThat(statusText).isEqualTo("CONFIRMED");

            Map<String, Object> second = outcomeOf(postMcp(callTool(tool, input), "admin-key", sid).getBody());
            assertThat(second).containsEntry("status", "executed");
            assertThat(second).containsEntry("confirmationId", confirmationId);

            assertThat(getConfirmation(confirmationId).getBody()).contains("EXECUTED");
        } finally {
            deleteTool(tool);
        }
    }

    @Test
    void rejectedPendingNeverExecutes() {
        String tool = "flow_tool_reject";
        String input = "{\"k\":\"r\"}";
        registerWriteTool(tool);
        try {
            String sid = init("admin-key");

            Map<String, Object> first = outcomeOf(postMcp(callTool(tool, input), "admin-key", sid).getBody());
            assertThat(first).containsEntry("status", "pending");
            String rejectedId = (String) first.get("confirmationId");

            assertThat(postConfirmation(rejectedId, "reject").getBody()).contains("REJECTED");

            // 拒绝后再次调用：不执行实际逻辑（无 result 字段），生成新的 pending
            Map<String, Object> second = outcomeOf(postMcp(callTool(tool, input), "admin-key", sid).getBody());
            assertThat(second).containsEntry("status", "pending");
            assertThat(second).doesNotContainKey("result");
            assertThat((String) second.get("confirmationId")).isNotEqualTo(rejectedId);

            // 被拒绝的记录保持 REJECTED
            assertThat(getConfirmation(rejectedId).getBody()).contains("REJECTED");
        } finally {
            deleteTool(tool);
        }
    }

    private void registerWriteTool(String name) {
        Map<String, Object> create = Map.of(
                "name", name,
                "description", "阶段三端到端探针",
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
        assertThat(sessionId).isNotBlank();
        postMcp("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", apiKey, sessionId);
        return sessionId;
    }

    private String callTool(String name, String argsJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name
                + "\",\"arguments\":" + argsJson + "}}";
    }

    /** 从 MCP 响应（SSE 或纯 JSON）中提取 content[0].text 的字符串内容。 */
    private String extractText(String body) {
        String data = body.lines()
                .filter(line -> line.startsWith("data:"))
                .findFirst()
                .map(line -> line.substring("data:".length()))
                .orElse(body);
        return JSON.readTree(data).at("/result/content/0/text").asString();
    }

    /** text 内容是 JSON 对象（SafeToolExecutor 的返回值）时反序列化成 Map。 */
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

    private ResponseEntity<String> postConfirmation(String id, String action) {
        try {
            return client.post().uri("/confirmations/{id}/{action}", id, action)
                    .header("X-API-Key", "admin-key")
                    .retrieve()
                    .toEntity(String.class);
        } catch (RestClientResponseException e) {
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        }
    }

    private ResponseEntity<String> getConfirmation(String id) {
        try {
            return client.get().uri("/confirmations/{id}", id)
                    .header("X-API-Key", "admin-key")
                    .retrieve()
                    .toEntity(String.class);
        } catch (RestClientResponseException e) {
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        }
    }
}
