package com.example.mcp.support;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 联邦/确认/审计类集成测试共用的 MCP HTTP 助手：
 * 会话初始化、tools/call、SSE data 行解析与内层 JSON 反序列化。
 */
public final class McpTestSupport {

    public static final String INIT_BODY =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\","
                    + "\"capabilities\":{},\"clientInfo\":{\"name\":\"it\",\"version\":\"0.0.1\"}}}";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private McpTestSupport() {
    }

    /** initialize + notifications/initialized，返回会话 ID。 */
    public static String init(RestClient client, String apiKey) {
        ResponseEntity<String> init = postMcp(client, INIT_BODY, apiKey, null);
        assertThat(init.getStatusCode().value()).isEqualTo(200);
        String sessionId = init.getHeaders().getFirst("Mcp-Session-Id");
        assertThat(sessionId).isNotBlank();
        postMcp(client, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", apiKey, sessionId);
        return sessionId;
    }

    public static String callToolBody(String tool, String argsJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":" + argsJson + "}}";
    }

    /** POST /mcp；4xx/5xx 不抛异常，转成带状态码的响应便于断言。 */
    public static ResponseEntity<String> postMcp(RestClient client, String body, String apiKey, String sessionId) {
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

    /** 从 MCP 响应（SSE 或纯 JSON）取出 data 行原文。 */
    public static String dataOf(String body) {
        return body.lines()
                .filter(line -> line.startsWith("data:"))
                .findFirst()
                .map(line -> line.substring("data:".length()))
                .orElse(body);
    }

    /** 取 content[0].text（tools/call 结果的文本载荷）。 */
    public static String textOf(String body) {
        return JSON.readTree(dataOf(body)).at("/result/content/0/text").asString();
    }

    /** text 内容是 JSON 对象时（SafeToolExecutor 的返回值）反序列化成 Map。 */
    public static Map<String, Object> outcomeOf(String body) {
        return JSON.readValue(textOf(body), MAP_TYPE);
    }

    /** tools/call 的 JSON-RPC 层断言视图：isError 与 text（纯文本，可能是错误消息）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> callTool(RestClient client, String apiKey, String sessionId,
                                               String tool, String argsJson) {
        Map<String, Object> root = jsonMap(
                postMcp(client, callToolBody(tool, argsJson), apiKey, sessionId).getBody());
        Map<String, Object> result = (Map<String, Object>) root.get("result");
        if (result != null) {
            List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
            String text = content == null || content.isEmpty() ? ""
                    : String.valueOf(((Map<String, Object>) content.get(0)).get("text"));
            return Map.of("isError", Boolean.TRUE.equals(result.get("isError")), "text", text);
        }
        return Map.of("isError", true, "text", String.valueOf(root.get("error")));
    }

    /** 把响应 data 行反序列化为完整 JSON-RPC 对象。 */
    public static Map<String, Object> jsonMap(String body) {
        return JSON.readValue(dataOf(body), MAP_TYPE);
    }
}
