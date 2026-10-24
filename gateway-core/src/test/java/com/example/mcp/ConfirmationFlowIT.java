package com.example.mcp;

import com.example.mcp.support.McpTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.example.mcp.support.McpTestSupport.callToolBody;
import static com.example.mcp.support.McpTestSupport.init;
import static com.example.mcp.support.McpTestSupport.outcomeOf;
import static com.example.mcp.support.McpTestSupport.postMcp;
import static com.example.mcp.support.McpTestSupport.textOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段三端到端：注册 WRITE 工具 → MCP 调用得 pending → REST confirm →
 * get_confirmation_status 查询 CONFIRMED → 再次 MCP 调用触发执行 EXECUTED；拒绝场景不执行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConfirmationFlowIT {

    private static final Pattern CONFIRMATION_ID =
            Pattern.compile("\"confirmationId\":\"([0-9a-fA-F-]{36})\"");

    @LocalServerPort
    int port;

    RestClient client;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void writeToolExecutesOnlyAfterConfirmation() {
        String tool = "flow_tool_confirm";
        String input = "{\"k\":\"v\"}";
        registerWriteTool(tool);
        try {
            String sid = init(client, "admin-key");

            Map<String, Object> first = outcomeOf(
                    postMcp(client, callToolBody(tool, input), "admin-key", sid).getBody());
            assertThat(first).containsEntry("status", "pending");
            String confirmationId = (String) first.get("confirmationId");

            ResponseEntity<String> confirmed = postConfirmation(confirmationId, "confirm");
            assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(confirmed.getBody()).contains("CONFIRMED");

            String statusText = textOf(postMcp(client,
                    callToolBody("get_confirmation_status", "{\"confirmationId\":\"" + confirmationId + "\"}"),
                    "admin-key", sid).getBody());
            assertThat(statusText).isEqualTo("CONFIRMED");

            Map<String, Object> second = outcomeOf(
                    postMcp(client, callToolBody(tool, input), "admin-key", sid).getBody());
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
            String sid = init(client, "admin-key");

            Map<String, Object> first = outcomeOf(
                    postMcp(client, callToolBody(tool, input), "admin-key", sid).getBody());
            assertThat(first).containsEntry("status", "pending");
            String rejectedId = (String) first.get("confirmationId");

            assertThat(postConfirmation(rejectedId, "reject").getBody()).contains("REJECTED");

            // 拒绝后再次调用：不执行实际逻辑（无 result 字段），生成新的 pending
            Map<String, Object> second = outcomeOf(
                    postMcp(client, callToolBody(tool, input), "admin-key", sid).getBody());
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
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
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
