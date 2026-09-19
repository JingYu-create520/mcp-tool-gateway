package com.example.mcp.upstream;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(UpstreamProperties.class)
public class UpstreamConfig {

    /**
     * 熔断注册表：resilience4j-spring-boot3 不支持 Boot 4（其 SpringBoot3Verifier 主动拒绝），
     * 因此用 core 模块手工装配，参数来自 gateway.upstreams.resilience.circuitbreaker。
     */
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(UpstreamProperties properties) {
        UpstreamProperties.CircuitBreaker settings = properties.resilience().circuitbreakerOrDefault();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(settings.slidingWindowSize() == null ? 10 : settings.slidingWindowSize())
                .failureRateThreshold(settings.failureRateThreshold() == null ? 50
                        : settings.failureRateThreshold().floatValue())
                .waitDurationInOpenState(Duration.ofSeconds(
                        settings.waitDurationInOpenStateSeconds() == null ? 30
                                : settings.waitDurationInOpenStateSeconds()))
                .permittedNumberOfCallsInHalfOpenState(
                        settings.permittedNumberOfCallsInHalfOpenState() == null ? 3
                                : settings.permittedNumberOfCallsInHalfOpenState())
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    /** 超时隔离注册表：upstream 调用超过 timeout-duration-seconds 即掐断并计为失败。 */
    @Bean
    public TimeLimiterRegistry timeLimiterRegistry(UpstreamProperties properties) {
        UpstreamProperties.TimeLimiter settings = properties.resilience().timelimiterOrDefault();
        TimeLimiterConfig config = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(
                        settings.timeoutDurationSeconds() == null ? 5 : settings.timeoutDurationSeconds()))
                .build();
        return TimeLimiterRegistry.of(config);
    }
}
