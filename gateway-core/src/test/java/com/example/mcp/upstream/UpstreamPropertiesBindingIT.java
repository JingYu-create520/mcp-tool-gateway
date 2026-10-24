package com.example.mcp.upstream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * upstream 配置绑定回归测试。
 * 回归背景：application.yml 曾把 resilience.circuitbreaker 写在 gateway.upstreams 直下，
 * 层级错位被静默忽略、参数全部回落默认值（恰好默认值相同而未暴露）。
 */
@SpringBootTest
@ActiveProfiles("test")
class UpstreamPropertiesBindingIT {

    @Autowired
    UpstreamProperties properties;

    @Test
    void yamlResilienceSettingsAreActuallyBound() {
        // 这些值与 compact constructor 的回退默认值(10/50/30/3/5)刻意不同：
        // 若 YAML 层级错位被静默忽略而回落默认值，此测试必须变红
        assertThat(properties.resilience().circuitbreaker().slidingWindowSize()).isEqualTo(7);
        assertThat(properties.resilience().circuitbreaker().failureRateThreshold()).isEqualTo(60);
        assertThat(properties.resilience().circuitbreaker().waitDurationInOpenStateSeconds()).isEqualTo(25);
        assertThat(properties.resilience().circuitbreaker().permittedNumberOfCallsInHalfOpenState())
                .isEqualTo(2);
        assertThat(properties.resilience().timelimiter().timeoutDurationSeconds()).isEqualTo(9);
    }

    @Test
    void upstreamListIsBound() {
        assertThat(properties.upstreams())
                .extracting(UpstreamProperties.Upstream::name)
                .containsExactlyInAnyOrder("time", "weather");
        assertThat(properties.upstreams())
                .allSatisfy(upstream -> {
                    assertThat(upstream.baseUrl()).isNotBlank();
                    assertThat(upstream.isEnabled()).isTrue();
                });
    }
}
