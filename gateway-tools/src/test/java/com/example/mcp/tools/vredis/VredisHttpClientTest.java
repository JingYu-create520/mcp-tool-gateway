package com.example.mcp.tools.vredis;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VredisHttpClient 的错误语义单元测试（纯 JUnit，不起 Spring 上下文）。
 * 三个错误路径都不允许抛异常穿透：后端必须返回结构化 vredis_unavailable JSON。
 * 覆盖背景：此前模块零测试，HTTP 非 2xx 分支无任何覆盖。
 */
class VredisHttpClientTest {

    private HttpServer stub;
    private int port;

    @BeforeEach
    void startStub() throws Exception {
        stub = HttpServer.create(new InetSocketAddress(0), 0);
        stub.createContext("/search", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean boom = body.contains("boom");
            byte[] response = (boom ? "backend exploded" : "{\"results\":[]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(boom ? 500 : 200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        stub.start();
        port = stub.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        stub.stop(0);
    }

    @Test
    void successfulCallPassesThrough() {
        VredisHttpClient client = new VredisHttpClient("http://localhost:" + port, 1000, 2000);
        assertThat(client.search("{\"pattern\":\"x\"}")).contains("results");
    }

    @Test
    void httpErrorReturnsStructuredJson() {
        VredisHttpClient client = new VredisHttpClient("http://localhost:" + port, 1000, 2000);
        assertThat(client.search("{\"pattern\":\"boom\"}"))
                .contains("vredis_unavailable")
                .contains("HTTP 500");
    }

    @Test
    void unreachableBackendReturnsStructuredJson() {
        // localhost:1 无监听：连接拒绝 → 结构化错误（超时 1 秒内快速失败）
        VredisHttpClient client = new VredisHttpClient("http://localhost:1", 1000, 2000);
        assertThat(client.search("{}"))
                .contains("vredis_unavailable")
                .contains("不可达");
    }

    @Test
    void blankBaseUrlReportsUnconfigured() {
        VredisHttpClient client = new VredisHttpClient("", 1000, 2000);
        assertThat(client.search("{}"))
                .contains("vredis_unavailable")
                .contains("未配置");
    }
}
