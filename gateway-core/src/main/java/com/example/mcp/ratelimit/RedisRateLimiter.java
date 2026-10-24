package com.example.mcp.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;


/**
 * 限流器统一接口的 Redis 实现：固定窗口计数经 Lua 脚本原子执行（INCR + 首次 EXPIRE），
 * 多实例共享同一计数——全局配额精确等于 rateLimit，与实例数无关。
 *
 * <p>选择 gateway.rate-limiter=redis 时生效，需部署 Redis（compose 已含）；
 * Redis 不可用时 INCR 调用抛异常，由调用方（SafeToolExecutor）转 isError——
 * 限流器故障不应静默放行（那会让配额形同虚设），也不应本地兜底计数（那会回到每实例失真）。
 */
@Component
@ConditionalOnProperty(name = "gateway.rate-limiter", havingValue = "redis")
public class RedisRateLimiter implements RateLimiter {

    private static final long WINDOW_MS = 60_000;

    // KEYS[1]=计数 key；ARGV[1]=窗口毫秒；INCR + 首次 PEXPIRE 原子完成
    private static final DefaultRedisScript<Long> INCR_SCRIPT = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) " +
            "if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end " +
            "return c", Long.class);

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquire(String toolName, String callerId, int rateLimit) {
        String key = "ratelimit:" + toolName + '|' + callerId + "|"
                + (System.currentTimeMillis() / WINDOW_MS);
        Long count = redis.execute(INCR_SCRIPT, java.util.List.of(key), String.valueOf(WINDOW_MS));
        // Redis 返回 null 仅在管道/事务场景，此处视为放行失败（保守拒绝由上层兜底）
        return count != null && count <= rateLimit;
    }
}
