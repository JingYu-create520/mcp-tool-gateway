package com.example.mcp.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UpstreamMetrics 健康 gauge 的取值路径（此前只有值域冒烟断言，
 * updateHealth 写入正确值的逻辑零覆盖——盲审 #3 指出）。
 */
class UpstreamMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final UpstreamMetrics metrics = new UpstreamMetrics(registry);

    @Test
    void updateHealthWritesOneForHealthyAndZeroForUnhealthy() {
        metrics.bindHealth("probe-time");

        metrics.updateHealth("probe-time", true);
        assertThat(gaugeValue("probe-time")).isEqualTo(1.0);

        metrics.updateHealth("probe-time", false);
        assertThat(gaugeValue("probe-time")).isEqualTo(0.0);
    }

    @Test
    void differentUpstreamsTrackIndependently() {
        metrics.updateHealth("probe-a", true);
        metrics.updateHealth("probe-b", false);

        assertThat(gaugeValue("probe-a")).isEqualTo(1.0);
        assertThat(gaugeValue("probe-b")).isEqualTo(0.0);
    }

    private double gaugeValue(String upstream) {
        var gauge = registry.find("mcp.upstream.healthy").tag("upstream", upstream).gauge();
        assertThat(gauge).as("gauge for upstream '%s' 应已注册", upstream).isNotNull();
        return gauge.value();
    }
}
