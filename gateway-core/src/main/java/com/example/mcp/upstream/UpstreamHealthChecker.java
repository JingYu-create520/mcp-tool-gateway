package com.example.mcp.upstream;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * upstream 健康检查：逐个 ping 并记录结果；每轮结束后触发联邦工具清单同步
 * （不健康的 upstream 自动从 tools/list 剔除，恢复后自动加回）。
 */
@Component
public class UpstreamHealthChecker {

    private final Map<String, Boolean> health = new ConcurrentHashMap<>();

    private final McpClientPool pool;
    private final FederatedToolRegistry federatedToolRegistry;

    public UpstreamHealthChecker(McpClientPool pool, FederatedToolRegistry federatedToolRegistry) {
        this.pool = pool;
        this.federatedToolRegistry = federatedToolRegistry;
    }

    @Scheduled(
            fixedDelayString = "${gateway.upstreams.health-check-fixed-delay-ms:30000}",
            initialDelayString = "${gateway.upstreams.health-check-initial-delay-ms:3000}")
    @SchedulerLock(name = "upstreamHealthCheck", lockAtMostFor = "1m", lockAtLeastFor = "5s")
    public void check() {
        pool.allClients().forEach(client -> health.put(client.getName(), client.ping()));
        federatedToolRegistry.syncUpstreamTools();
    }

    public boolean isHealthy(String name) {
        return health.getOrDefault(name, true);
    }

    public Map<String, Boolean> snapshot() {
        return Map.copyOf(health);
    }
}
