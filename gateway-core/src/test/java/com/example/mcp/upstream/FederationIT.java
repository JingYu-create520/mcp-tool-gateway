package com.example.mcp.upstream;

import com.example.mcp.McpGatewayApplication;
import com.example.upstream.time.TimeUpstreamApplication;
import com.example.upstream.weather.WeatherUpstreamApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具联邦端到端：同进程启动 Gateway + 两个真实 upstream MCP Server（Spring AI），
 * 验证工具清单联邦、调用路由、熔断（isError:true）与健康剔除。
 */
@SpringBootTest(classes = McpGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FederationIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final String INIT_BODY =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\","
                    + "\"capabilities\":{},\"clientInfo\":{\"name\":\"it\",\"version\":\"0.0.1\"}}}";

    static ConfigurableApplicationContext timeApp;
    static ConfigurableApplicationContext weatherApp;
    static int timePort;
    static int weatherPort;

    @AfterAll
    static void stopUpstreams() {
        if (timeApp != null) {
            timeApp.close();
        }
        if (weatherApp != null) {
            weatherApp.close();
        }
    }

    @DynamicPropertySource
    static void federationProps(DynamicPropertyRegistry registry) {
        // 必须在这里（Gateway 上下文装配期间）拉起 upstream：
        // DynamicPropertySource 存在时，上下文先于 @BeforeAll 创建，
        // 而 FederatedToolRegistry 的 ApplicationRunner 在启动时就要连接 upstream。
        // 端口用命令行参数覆盖（--server.port=0 优先级高于 yml 里的 8090/8091）。
        timeApp = new SpringApplicationBuilder(TimeUpstreamApplication.class)
                .run("--spring.config.name=upstream-time", "--server.port=0");
        timePort = Integer.parseInt(timeApp.getEnvironment().getProperty("local.server.port"));
        weatherApp = new SpringApplicationBuilder(WeatherUpstreamApplication.class)
                .run("--spring.config.name=upstream-weather", "--server.port=0");
        weatherPort = Integer.parseInt(weatherApp.getEnvironment().getProperty("local.server.port"));

        // 健康检查加速到 1 秒一轮（默认 30 秒）
        registry.add("gateway.upstreams.health-check-fixed-delay-ms", () -> "1000");
        registry.add("gateway.upstreams.upstreams[0].name", () -> "time");
        registry.add("gateway.upstreams.upstreams[0].base-url", () -> "http://localhost:" + timePort + "/mcp");
        registry.add("gateway.upstreams.upstreams[0].enabled", () -> "true");
        registry.add("gateway.upstreams.upstreams[1].name", () -> "weather");
        registry.add("gateway.upstreams.upstreams[1].base-url", () -> "http://localhost:" + weatherPort + "/mcp");
        registry.add("gateway.upstreams.upstreams[1].enabled", () -> "true");
    }

    @LocalServerPort
    int port;

    RestClient client;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void federatesUpstreamToolsWithRoutingBreakerAndHealthRemoval() {
        String sessionId = init("user-key");

        // 1. 工具清单联邦：upstream 工具 + 本地工具并存
        Set<String> names = toolsListNames(sessionId);
        assertThat(names).contains("time__now", "weather__forecast",
                "echo", "vredis_search", "vredis_upsert", "vredis_delete", "get_confirmation_status");

        // 2. 调用 time__now → 路由到 time upstream 并返回结果
        Map<String, Object> now = callTool("user-key", sessionId, "time__now", "{}");
        assertThat(now.get("isError")).as("text=%s", now.get("text")).isEqualTo(false);
        // 外层是 SafeToolExecutor 的包装 {status, result}，result 是 UpstreamToolRouter 的 JSON 字符串
        Map<String, Object> routed = JSON.readValue(String.valueOf(now.get("text")), MAP_TYPE);
        assertThat((String) routed.get("result"))
                .contains("\"upstream\":\"time\"")
                .contains("\"tool\":\"now\"")
                .contains("20"); // 时间戳年份

        // 3. 调用 weather__forecast → 带参数正常返回
        Map<String, Object> forecast = callTool("user-key", sessionId, "weather__forecast",
                "{\"city\":\"Shanghai\"}");
        assertThat(forecast.get("isError")).isEqualTo(false);
        assertThat(String.valueOf(forecast.get("text"))).contains("Shanghai");

        // 4. 关掉 time upstream → 立即调用触发熔断统计，返回 isError:true
        timeApp.close();
        for (int i = 0; i < 6; i++) {
            Map<String, Object> failed = callTool("user-key", sessionId, "time__now", "{}");
            assertThat(failed.get("isError")).as("第 %d 次失败调用应返回 isError", i).isEqualTo(true);
        }

        // 5. 健康检查剔除：10 秒内 time__now 从 tools/list 消失，其他工具不受影响
        Set<String> afterRemoval = null;
        for (int i = 0; i < 10; i++) {
            afterRemoval = toolsListNames(sessionId);
            if (!afterRemoval.contains("time__now")) {
                break;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(afterRemoval).isNotNull().doesNotContain("time__now");
        assertThat(afterRemoval).contains("weather__forecast", "echo", "vredis_search", "weather__forecast");

        // 6. 剔除后再调用 → MCP 层直接报未知工具（JSON-RPC error，code -32602）
        Map<String, Object> gone = callTool("user-key", sessionId, "time__now", "{}");
        assertThat(String.valueOf(gone.get("text")))
                .contains("Unknown tool")
                .contains("time__now");
    }

    private String init(String apiKey) {
        var init = postMcp(INIT_BODY, apiKey, null);
        String sessionId = init.getHeaders().getFirst("Mcp-Session-Id");
        postMcp("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", apiKey, sessionId);
        return sessionId;
    }

    private Set<String> toolsListNames(String sessionId) {
        ResponseEntity<String> response = postMcp(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", "user-key", sessionId);
        String data = dataLine(response.getBody());
        Map<String, Object> root = JSON.readValue(data, MAP_TYPE);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) root.get("tools");
        if (tools == null) {
            tools = (List<Map<String, Object>>) ((Map<?, ?>) root.get("result")).get("tools");
        }
        return tools.stream().map(t -> (String) t.get("name")).collect(Collectors.toSet());
    }

    private Map<String, Object> callTool(String apiKey, String sessionId, String tool, String args) {
        ResponseEntity<String> response = postMcp(
                "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                        + "\",\"arguments\":" + args + "}}",
                apiKey, sessionId);
        String data = dataLine(response.getBody());
        Map<String, Object> root = JSON.readValue(data, MAP_TYPE);
        Map<String, Object> result = (Map<String, Object>) root.get("result");
        Map<String, Object> error = (Map<String, Object>) root.get("error");
        if (result != null) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
            String text = content == null || content.isEmpty() ? ""
                    : String.valueOf(((Map<String, Object>) content.get(0)).get("text"));
            return Map.of("isError", Boolean.TRUE.equals(result.get("isError")), "text", text);
        }
        return Map.of("isError", true, "text", String.valueOf(error));
    }

    private String dataLine(String body) {
        return body.lines()
                .filter(line -> line.startsWith("data:"))
                .findFirst()
                .map(line -> line.substring("data:".length()))
                .orElse(body);
    }

    private ResponseEntity<String> postMcp(String body, String apiKey, String sessionId) {
        try {
            var request = client.post().uri("/mcp")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .accept(org.springframework.http.MediaType.APPLICATION_JSON,
                            org.springframework.http.MediaType.TEXT_EVENT_STREAM);
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
}
