package com.example.mcp.upstream;

import com.example.mcp.McpGatewayApplication;
import com.example.mcp.support.McpTestSupport;
import com.example.upstream.time.TimeUpstreamApplication;
import com.example.upstream.weather.WeatherUpstreamApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Set;

import static com.example.mcp.support.McpTestSupport.callToolBody;
import static com.example.mcp.support.McpTestSupport.init;
import static com.example.mcp.support.McpTestSupport.jsonMap;
import static com.example.mcp.support.McpTestSupport.outcomeOf;
import static com.example.mcp.support.McpTestSupport.postMcp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 工具联邦端到端：同进程启动 Gateway + 两个真实 upstream MCP Server（Spring AI），
 * 验证工具清单联邦、调用路由、熔断（isError:true）与健康剔除。
 */
@SpringBootTest(classes = McpGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FederationIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static ConfigurableApplicationContext timeApp;
    private static ConfigurableApplicationContext weatherApp;
    private static int timePort;
    private static int weatherPort;

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
        // 独立 H2 库名，避免与共享 mcp_test 库的跨上下文状态污染
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:federation_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
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
        String sessionId = init(client, "user-key");

        // 1. 工具清单联邦：upstream 工具 + 本地工具并存
        Set<String> names = toolsListNames(sessionId);
        assertThat(names).contains("time__now", "weather__forecast",
                "echo", "vredis_search", "vredis_upsert", "vredis_delete", "get_confirmation_status");

        // 2. 调用 time__now → 路由到 time upstream 并返回结果
        Map<String, Object> now = outcomeOf(
                postMcp(client, callToolBody("time__now", "{}"), "user-key", sessionId).getBody());
        assertThat(now).containsEntry("status", "executed");
        // 外层是 SafeToolExecutor 的包装 {status, result}，result 是 UpstreamToolRouter 的 JSON 字符串
        Map<String, Object> routed = JSON.readValue(String.valueOf(now.get("result")), McpTestSupport.MAP_TYPE);
        assertThat(routed.get("upstream")).isEqualTo("time");
        assertThat(routed.get("tool")).isEqualTo("now");
        // 时间戳断言不锚定年份（否则 2027-01-01 起必挂）：必须是可解析的 ISO-8601
        assertThatCode(() -> java.time.Instant.parse(String.valueOf(routed.get("result"))))
                .doesNotThrowAnyException();

        // 3. 调用 weather__forecast → 带参数正常返回
        Map<String, Object> forecast = outcomeOf(
                postMcp(client, callToolBody("weather__forecast", "{\"city\":\"Shanghai\"}"), "user-key", sessionId).getBody());
        assertThat(forecast).containsEntry("status", "executed");
        assertThat(String.valueOf(forecast.get("result"))).contains("Shanghai");

        // 4. 关掉 time upstream → 立即调用触发熔断统计，返回 isError:true
        timeApp.close();
        for (int i = 0; i < 6; i++) {
            Map<String, Object> failed = McpTestSupport.callTool(
                    client, "user-key", sessionId, "time__now", "{}");
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
        assertThat(afterRemoval).contains("weather__forecast", "echo", "vredis_search");

        // 6. 剔除后再调用 → MCP 层直接报未知工具（JSON-RPC error，code -32602）
        Map<String, Object> gone = jsonMap(
                postMcp(client, callToolBody("time__now", "{}"), "user-key", sessionId).getBody());
        assertThat(String.valueOf(gone.get("error")))
                .contains("Unknown tool")
                .contains("time__now");
    }

    private Set<String> toolsListNames(String sessionId) {
        Map<String, Object> root = McpTestSupport.jsonMap(
                postMcp(client, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", "user-key", sessionId).getBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) root.get("result");
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> tools = (java.util.List<Map<String, Object>>) result.get("tools");
        return tools.stream().map(t -> (String) t.get("name")).collect(java.util.stream.Collectors.toSet());
    }
}
