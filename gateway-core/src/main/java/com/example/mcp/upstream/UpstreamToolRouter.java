package com.example.mcp.upstream;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * upstream 调用路由：按 upstream 名称找到客户端，调用包上 超时隔离（TimeLimiter）
 * 与熔断（CircuitBreaker）。任何失败（连接拒绝/超时/熔断打开/工具错误）都转成
 * IllegalArgumentException，由 DynamicToolRegistry 的处理器转成 isError:true 的
 * MCP 工具结果——与本地工具的错误语义一致。
 */
@Component
public class UpstreamToolRouter {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> ARGS_TYPE = new TypeReference<>() {
    };

    private final McpClientPool pool;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final TimeLimiterRegistry timeLimiterRegistry;

    public UpstreamToolRouter(McpClientPool pool,
                              CircuitBreakerRegistry circuitBreakerRegistry,
                              TimeLimiterRegistry timeLimiterRegistry) {
        this.pool = pool;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.timeLimiterRegistry = timeLimiterRegistry;
    }

    /** 供 DynamicToolRegistry 绑定到联邦工具的执行器。 */
    public Function<String, String> executorFor(String upstreamName, String upstreamToolName) {
        return inputJson -> route(upstreamName, upstreamToolName, inputJson);
    }

    public String route(String upstreamName, String upstreamToolName, String inputJson) {
        McpUpstreamClient client = pool.byName(upstreamName);
        if (client == null) {
            throw new IllegalArgumentException("unknown upstream: " + upstreamName);
        }
        try {
            Supplier<String> call = () -> {
                Map<String, Object> args = inputJson == null || inputJson.isBlank()
                        ? Map.of()
                        : JSON.readValue(inputJson, ARGS_TYPE);
                McpSchema.CallToolResult result = client.callTool(upstreamToolName, args);
                if (Boolean.TRUE.equals(result.isError())) {
                    throw new IllegalStateException(textOf(result));
                }
                String text = textOf(result);
                return JSON.writeValueAsString(
                        Map.of("upstream", upstreamName, "tool", upstreamToolName, "result", text));
            };
            // 超时隔离在内（慢调用 5s 掐断），熔断在外（失败率统计含超时）
            Supplier<String> timed = () -> {
                try {
                    return timeLimiter(upstreamName)
                            .executeFutureSupplier(() -> CompletableFuture.supplyAsync(call));
                } catch (Exception e) {
                    if (e instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    throw new IllegalStateException(e);
                }
            };
            return circuitBreaker(upstreamName).executeSupplier(timed::get);
        } catch (Exception e) {
            client.reset();
            throw new IllegalArgumentException(
                    "upstream '" + upstreamName + "' 不可用: " + rootMessage(e), e);
        }
    }

    private CircuitBreaker circuitBreaker(String upstreamName) {
        return circuitBreakerRegistry.circuitBreaker("upstream-" + upstreamName, "default");
    }

    private TimeLimiter timeLimiter(String upstreamName) {
        return timeLimiterRegistry.timeLimiter("upstream-" + upstreamName, "default");
    }

    private static String textOf(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(c -> ((McpSchema.TextContent) c).text())
                .reduce((a, b) -> a + "; " + b)
                .orElse("{}");
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
