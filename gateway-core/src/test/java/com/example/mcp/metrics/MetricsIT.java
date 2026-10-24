package com.example.mcp.metrics;

import com.example.mcp.McpGatewayApplication;
import com.example.mcp.support.McpTestSupport;
import com.example.mcp.upstream.UpstreamHealthChecker;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import static com.example.mcp.support.McpTestSupport.callToolBody;
import static com.example.mcp.support.McpTestSupport.init;
import static com.example.mcp.support.McpTestSupport.postMcp;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可观测性端到端：通过真实 MCP 调用驱动埋点，断言 Prometheus 指标存在且计数正确。
 * 用增量断言（before/after 差值），因为共享上下文里其他 IT 可能已累计计数。
 */
@SpringBootTest(classes = McpGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MetricsIT {

    @LocalServerPort
    int port;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    UpstreamHealthChecker healthChecker;

    RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void toolAndUpstreamMetricsAreRecorded() {
        // 共享上下文里其他 IT 可能已累计计数，这里以差值断言本测试的调用
        double echoBefore = echoCounter().count();
        double pendingBefore = pendingCounter().count();
        double deniedBefore = deniedCounter().count();

        // 1. echo 调用 3 次（READ，success）
        String sid = init(client, "user-key");
        for (int i = 0; i < 3; i++) {
            postMcp(client, callToolBody("echo", "{\"message\":\"metrics-" + i + "\"}"), "user-key", sid);
        }
        assertThat(echoCounter().count() - echoBefore).isEqualTo(3.0);

        // 2. admin 调 vredis_upsert → pending 计数
        postMcp(client, callToolBody("vredis_upsert", "{\"key\":\"metrics:key\",\"value\":\"v\"}"),
                "admin-key", init(client, "admin-key"));
        assertThat(pendingCounter().count() - pendingBefore).isEqualTo(1.0);

        // 3. user 越权调用 → permission_denied 计数
        postMcp(client, callToolBody("vredis_upsert", "{\"key\":\"metrics:key\",\"value\":\"v\"}"),
                "user-key", sid);
        assertThat(deniedCounter().count() - deniedBefore).isEqualTo(1.0);

        // 4. upstream 健康 Gauge 存在（本机 8090/8091 无服务 → 值为 0）
        healthChecker.check();
        assertThat(meterRegistry.find("mcp.upstream.healthy").meters()).isNotEmpty();
        meterRegistry.find("mcp.upstream.healthy").meters()
                .forEach(meter -> assertThat(((io.micrometer.core.instrument.Gauge) meter).value())
                        .isIn(0.0, 1.0));
    }

    private io.micrometer.core.instrument.Counter echoCounter() {
        return meterRegistry.counter("mcp.tool.invocations",
                "tool", "echo", "side_effect", "READ", "result", "success");
    }

    private io.micrometer.core.instrument.Counter pendingCounter() {
        return meterRegistry.counter("mcp.tool.invocations",
                "tool", "vredis_upsert", "side_effect", "WRITE", "result", "pending");
    }

    private io.micrometer.core.instrument.Counter deniedCounter() {
        return meterRegistry.counter("mcp.tool.invocations",
                "tool", "vredis_upsert", "side_effect", "WRITE", "result", "permission_denied");
    }
}
