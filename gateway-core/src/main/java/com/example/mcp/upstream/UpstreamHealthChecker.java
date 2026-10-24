package com.example.mcp.upstream;

import com.example.mcp.metrics.UpstreamMetrics;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * upstream 健康检查：逐个 ping 并通过 UpstreamMetrics 更新健康 Gauge；
 * 每轮结束后触发联邦工具清单同步（不健康的 upstream 自动从 tools/list 剔除，
 * 恢复后自动加回）。
 */
@Component
public class UpstreamHealthChecker {

    private final McpClientPool pool;
    private final FederatedToolRegistry federatedToolRegistry;
    private final UpstreamMetrics upstreamMetrics;

    public UpstreamHealthChecker(McpClientPool pool,
                                 FederatedToolRegistry federatedToolRegistry,
                                 UpstreamMetrics upstreamMetrics) {
        this.pool = pool;
        this.federatedToolRegistry = federatedToolRegistry;
        this.upstreamMetrics = upstreamMetrics;
        // 健康 Gauge 随上下文启动即注册（初值 1，首轮 ping 后更新为真实状态）
        pool.allClients().forEach(client -> upstreamMetrics.bindHealth(client.getName()));
    }

    @Scheduled(
            fixedDelayString = "${gateway.upstreams.health-check-fixed-delay-ms:30000}",
            initialDelayString = "${gateway.upstreams.health-check-initial-delay-ms:3000}")
    @SchedulerLock(name = "upstreamHealthCheck", lockAtMostFor = "1m", lockAtLeastFor = "5s")
    public void check() {
        pool.allClients().forEach(client -> {
            boolean healthy = client.ping();
            upstreamMetrics.updateHealth(client.getName(), healthy);
        });
        federatedToolRegistry.syncUpstreamTools();
    }
}
