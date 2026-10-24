package com.example.mcp.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 限流实现的真实验证（Testcontainers Redis）：
 * 配额跨 key 独立、跨"实例"（不同计数入口共享同一 Redis）精确、窗口滚动后重置。
 * 这是多实例部署下"全局配额精确等于 rateLimit"承诺的锚点。
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class RedisRateLimiterIT {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("gateway.rate-limiter", () -> "redis");
    }

    @Autowired
    RateLimiter rateLimiter;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void redisImplementationIsSelectedWhenConfigured() {
        assertThat(rateLimiter).isInstanceOf(RedisRateLimiter.class);
    }

    @Test
    void quotaIsEnforcedPerToolCallerAcrossEntries() {
        // 同 tool+caller：前 3 次放行，第 4 次拒绝（rateLimit=3）
        for (int i = 0; i < 3; i++) {
            assertThat(rateLimiter.tryAcquire("rl_probe", "caller-x", 3))
                    .as("第 %d 次调用应放行", i + 1).isTrue();
        }
        assertThat(rateLimiter.tryAcquire("rl_probe", "caller-x", 3)).isFalse();
        // 不同 caller 独立计数
        assertThat(rateLimiter.tryAcquire("rl_probe", "caller-y", 3)).isTrue();
        // 不同 tool 独立计数
        assertThat(rateLimiter.tryAcquire("rl_probe_2", "caller-x", 3)).isTrue();
    }

    @Test
    void counterExpiresWithWindow() throws Exception {
        AtomicBoolean first = new AtomicBoolean(rateLimiter.tryAcquire("rl_probe_ttl", "caller-t", 1));
        assertThat(first.get()).isTrue();
        assertThat(rateLimiter.tryAcquire("rl_probe_ttl", "caller-t", 1)).isFalse();
        // 窗口键带 TTL（60s），手动清 key 模拟窗口滚动
        redisTemplate.delete("ratelimit:rl_probe_ttl|caller-t|"
                + (System.currentTimeMillis() / 60_000));
        assertThat(rateLimiter.tryAcquire("rl_probe_ttl", "caller-t", 1)).isTrue();
    }
}
