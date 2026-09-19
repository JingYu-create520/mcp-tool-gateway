package com.example.mcp.tools.vredis;

import com.github.tomakehurst.wiremock.WireMockServer;

import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * 测试夹具：内存 WireMock，stub vredis 的四个 HTTP 端点。
 * start() 随机端口，baseUrl() 供 vredis.base-url 注入（@DynamicPropertySource）。
 * 注意：必须用实例 API（server.stubFor），静态 stubFor 会打到默认 8080 而非动态端口。
 */
public class MockVredisServer {

    private final WireMockServer server = new WireMockServer(options().dynamicPort());

    public void start() {
        server.start();
        server.stubFor(post(urlEqualTo("/search"))
                .willReturn(okJson("{\"results\":[]}")));
        server.stubFor(post(urlEqualTo("/upsert"))
                .willReturn(okJson("{\"ok\":true,\"id\":\"mock-id\"}")));
        server.stubFor(delete(urlEqualTo("/delete"))
                .willReturn(okJson("{\"ok\":true}")));
        server.stubFor(get(urlEqualTo("/stats"))
                .willReturn(okJson("{\"count\":0}")));
    }

    public void stop() {
        server.stop();
    }

    public String baseUrl() {
        return server.baseUrl();
    }

    public int port() {
        return server.port();
    }
}
