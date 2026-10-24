package com.example.mcp.upstream;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import com.example.mcp.metrics.UpstreamMetrics;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

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

    private static final JsonMapper JSON = com.example.mcp.support.JsonMappers.MAPPER;
    private static final TypeReference<Map<String, Object>> ARGS_TYPE = new TypeReference<>() {
    };

    private final McpClientPool pool;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final TimeLimiterRegistry timeLimiterRegistry;
    private final UpstreamMetrics upstreamMetrics;

    public UpstreamToolRouter(McpClientPool pool,
                              CircuitBreakerRegistry circuitBreakerRegistry,
                              TimeLimiterRegistry timeLimiterRegistry,
                              UpstreamMetrics upstreamMetrics) {
        this.pool = pool;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.timeLimiterRegistry = timeLimiterRegistry;
        this.upstreamMetrics = upstreamMetrics;
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
        // 入参解析在熔断/重试路径之外：调用方的 JSON 写错是 invalid_request，
        // 与 upstream 健康无关 —— 不该计熔断失败率，更不该重置会话
        Map<String, Object> args;
        try {
            args = inputJson == null || inputJson.isBlank() ? Map.of() : JSON.readValue(inputJson, ARGS_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "工具入参不是合法 JSON 对象: " + rootMessage(e), e);
        }
        long start = System.nanoTime();
        try {
            Supplier<String> call = () -> {
                McpSchema.CallToolResult result = client.callTool(upstreamToolName, args);
                // isError=true 是 upstream 的业务级错误结果（如参数校验失败）：
                // 传输本身是成功的，不重置会话、不计熔断失败率 —— 否则频繁的参数错误
                // 会把一个完全健康的 upstream 熔断掉
                String text = textOf(result);
                return Boolean.TRUE.equals(result.isError())
                        ? JSON.writeValueAsString(Map.of(
                                "upstream", upstreamName, "tool", upstreamToolName,
                                "isError", true, "result", text))
                        : JSON.writeValueAsString(Map.of(
                                "upstream", upstreamName, "tool", upstreamToolName, "result", text));
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
            CircuitBreaker breaker = circuitBreaker(upstreamName);
            upstreamMetrics.bindCircuitBreakerState(upstreamName, breaker);
            String outcome = breaker.executeSupplier(timed::get);
            upstreamMetrics.recordCall(upstreamName, "success", (System.nanoTime() - start) / 1_000_000);
            return outcome;
        } catch (CallNotPermittedException open) {
            // 熔断打开不是 upstream 故障信号：保留可能健康的会话，half-open 探测不必重新握手
            upstreamMetrics.recordCall(upstreamName, "error", (System.nanoTime() - start) / 1_000_000);
            throw new IllegalArgumentException(
                    "upstream '" + upstreamName + "' 熔断打开，请稍后重试", open);
        } catch (Exception e) {
            upstreamMetrics.recordCall(upstreamName, "error", (System.nanoTime() - start) / 1_000_000);
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
