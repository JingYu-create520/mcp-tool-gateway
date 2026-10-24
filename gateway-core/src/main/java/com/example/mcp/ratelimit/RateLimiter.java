package com.example.mcp.ratelimit;

/**
 * 限流器统一接口：按 tool + caller 维度做每次调用的放行决策。
 * 实现选择经 gateway.rate-limiter 配置：local（单实例内存，默认）/ redis（多实例全局精确）。
 */
public interface RateLimiter {

    /** @return true 放行；false 该窗口内配额已用尽 */
    boolean tryAcquire(String toolName, String callerId, int rateLimit);
}
