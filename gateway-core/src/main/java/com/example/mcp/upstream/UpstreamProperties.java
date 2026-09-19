package com.example.mcp.upstream;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 工具联邦的 upstream 配置（YAML 静态声明，不做动态注册）。
 * resilience 为 Resilience4j core 模块的熔断/超时设置（spring-boot3 集成模块不支持 Boot 4，
 * Registry Bean 由 UpstreamConfig 手工装配）。
 */
@ConfigurationProperties(prefix = "gateway.upstreams")
public record UpstreamProperties(List<Upstream> upstreams, Resilience resilience) {

    public UpstreamProperties {
        upstreams = upstreams == null ? List.of() : List.copyOf(upstreams);
        resilience = resilience == null ? Resilience.defaults() : resilience;
    }

    public record Resilience(CircuitBreaker circuitbreaker, TimeLimiter timelimiter) {

        public static Resilience defaults() {
            return new Resilience(new CircuitBreaker(10, 50, 30, 3), new TimeLimiter(5));
        }

        public CircuitBreaker circuitbreakerOrDefault() {
            return circuitbreaker == null ? new CircuitBreaker(10, 50, 30, 3) : circuitbreaker;
        }

        public TimeLimiter timelimiterOrDefault() {
            return timelimiter == null ? new TimeLimiter(5) : timelimiter;
        }
    }

    public record CircuitBreaker(
            Integer slidingWindowSize,
            Integer failureRateThreshold,
            Integer waitDurationInOpenStateSeconds,
            Integer permittedNumberOfCallsInHalfOpenState) {
    }

    public record TimeLimiter(Integer timeoutDurationSeconds) {
    }

    public record Upstream(
            String name,
            String baseUrl,
            String apiKey,
            Boolean enabled,
            Integer healthCheckIntervalSeconds,
            String defaultRequiredRole) {

        public boolean isEnabled() {
            return enabled == null || enabled;
        }

        public String defaultRole() {
            return defaultRequiredRole == null ? "user" : defaultRequiredRole;
        }
    }
}
