package com.example.mcp.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 工具调用埋点（旁路，不改变任何调用语义）：
 * - mcp_tool_invocations_total{tool, side_effect, result}：调用次数（Prometheus 命名）
 * - mcp_tool_duration_seconds{tool, side_effect}：延迟分布（histogram 已启用，支持 P50/P95/P99）
 * - mcp_tool_pending_duration_seconds：pending 从创建到确认的等待时长
 */
@Component
public class ToolMetrics {

    private final MeterRegistry registry;

    public ToolMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 记录一次工具调用。result ∈ success / error / permission_denied / pending；
     * durationMs 为 null 表示调用尚未完成（如进入 pending），不记延迟。
     */
    public void recordCall(String tool, String sideEffect, String result, Long durationMs) {
        io.micrometer.core.instrument.Counter.builder("mcp.tool.invocations")
                .tag("tool", tool)
                .tag("side_effect", sideEffect)
                .tag("result", result)
                .register(registry)
                .increment();
        if (durationMs != null) {
            io.micrometer.core.instrument.Timer.builder("mcp.tool.duration")
                    .tag("tool", tool)
                    .tag("side_effect", sideEffect)
                    .register(registry)
                    .record(Duration.ofMillis(durationMs));
        }
    }

    /** pending 被确认时记录等待时长。 */
    public void recordPendingConfirmed(Instant pendingCreatedAt) {
        io.micrometer.core.instrument.Timer.builder("mcp.tool.pending.duration")
                .register(registry)
                .record(Duration.between(pendingCreatedAt, Instant.now()));
    }
}
