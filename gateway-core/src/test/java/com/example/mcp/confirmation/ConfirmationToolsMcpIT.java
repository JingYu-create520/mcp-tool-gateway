package com.example.mcp.confirmation;

import com.example.mcp.support.McpTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static com.example.mcp.support.McpTestSupport.callTool;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * get_confirmation_status 走真实 MCP 传输的错误路径：
 * 坏 UUID / 不存在的 id 必须表现为工具级错误（isError 或明确错误文本），
 * 而不是异常穿透 handler 破坏传输层。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConfirmationToolsMcpIT {

    @LocalServerPort
    int port;

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void malformedUuidIsToolLevelErrorNotProtocolCrash() {
        RestClient client = client();
        String sessionId = McpTestSupport.init(client, "admin-key");

        Map<String, Object> result = callTool(client, "admin-key", sessionId, "get_confirmation_status",
                "{\"confirmationId\":\"not-a-uuid\"}");

        assertThat(result).containsKey("isError");
        // 要么 isError=true，要么正常返回但内容明确指出问题 —— 不允许的是传输层崩溃
        String text = String.valueOf(result.get("text"));
        assertThat(Boolean.TRUE.equals(result.get("isError")) || text.toLowerCase().contains("error"))
                .as("text=%s, isError=%s", text, result.get("isError")).isTrue();
    }

    @Test
    void unknownButWellFormedUuidIsToolLevelError() {
        RestClient client = client();
        String sessionId = McpTestSupport.init(client, "admin-key");

        Map<String, Object> result = callTool(client, "admin-key", sessionId, "get_confirmation_status",
                "{\"confirmationId\":\"" + java.util.UUID.randomUUID() + "\"}");

        assertThat(result).containsKey("isError");
    }
}
