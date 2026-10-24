package com.example.mcp.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 限流器统一接口的默认实现：tool + caller 维度独立计数，窗口固定 60 秒。
 *
 * <p>多实例部署时各实例独立计数（全局实际限额约为 rateLimit × 实例数）；
 * 精确的全局限额需要 Redis/DB 计数器，当前部署形态（单实例 compose）下内存版足够。
 * 窗口计数仅用于放行决策，进程重启即清零 —— 限流是尽力而为的保护，不是审计数据。
 */
@Component
@ConditionalOnProperty(name = "gateway.rate-limiter", havingValue = "local", matchIfMissing = true)
public class LocalRateLimiter implements RateLimiter {

    private static final long WINDOW_MS = 60_000;

    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String toolName, String callerId, int rateLimit) {
        if (rateLimit <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        // compute 对同一 key 互斥：计数与放行决策在同一临界区内完成，并发调用不会同时进入
        boolean[] allowed = new boolean[1];
        windows.compute(toolName + '|' + callerId, (key, current) -> {
            if (current == null || now - current.windowStart() >= WINDOW_MS) {
                allowed[0] = true;
                return new Window(now, 1);
            }
            if (current.count() >= rateLimit) {
                allowed[0] = false;
                return current;
            }
            allowed[0] = true;
            return new Window(current.windowStart(), current.count() + 1);
        });
        return allowed[0];
    }

    private record Window(long windowStart, int count) {}
}
