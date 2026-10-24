package com.example.mcp.metrics;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * upstream 埋点（旁路）：
 * - mcp_upstream_invocations_total{upstream, result}：调用次数（result=success/error）
 * - mcp_upstream_duration_seconds{upstream}：调用延迟分布（histogram 已启用）
 * - mcp_upstream_healthy{upstream}：健康状态 Gauge（1=健康，0=不健康）
 * - mcp_upstream_circuit_breaker_state{upstream, state}：熔断状态 Gauge（1=当前处于该状态）
 */
@Component
public class UpstreamMetrics {

    private static final List<CircuitBreaker.State> TRACKED_STATES =
            List.of(CircuitBreaker.State.CLOSED, CircuitBreaker.State.OPEN, CircuitBreaker.State.HALF_OPEN);

    private final MeterRegistry registry;
    private final Map<String, AtomicInteger> healthGauges = new ConcurrentHashMap<>();
    private final Set<String> circuitBreakersBound = ConcurrentHashMap.newKeySet();

    public UpstreamMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordCall(String upstream, String result, long durationMs) {
        io.micrometer.core.instrument.Counter.builder("mcp.upstream.invocations")
                .tag("upstream", upstream)
                .tag("result", result)
                .register(registry)
                .increment();
        io.micrometer.core.instrument.Timer.builder("mcp.upstream.duration")
                .tag("upstream", upstream)
                .register(registry)
                .record(Duration.ofMillis(durationMs));
    }

    /** 注册健康 Gauge（每个 upstream 一次），返回可写的状态句柄。 */
    public AtomicInteger bindHealth(String upstream) {
        return healthGauges.computeIfAbsent(upstream, name -> {
            AtomicInteger value = new AtomicInteger(1);
            Gauge.builder("mcp.upstream.healthy", value, AtomicInteger::get)
                    .tag("upstream", name)
                    .register(registry);
            return value;
        });
    }

    public void updateHealth(String upstream, boolean healthy) {
        bindHealth(upstream).set(healthy ? 1 : 0);
    }

    /** 为 upstream 的熔断器注册状态 Gauge（每个 upstream 一次，按 state 各一个）。 */
    public void bindCircuitBreakerState(String upstream, CircuitBreaker circuitBreaker) {
        if (!circuitBreakersBound.add(upstream)) {
            return;
        }
        for (CircuitBreaker.State state : TRACKED_STATES) {
            Gauge.builder("mcp.upstream.circuit_breaker.state", circuitBreaker, cb -> cb.getState() == state ? 1 : 0)
                    .tag("upstream", upstream)
                    .tag("state", state.name())
                    .register(registry);
        }
    }
}
