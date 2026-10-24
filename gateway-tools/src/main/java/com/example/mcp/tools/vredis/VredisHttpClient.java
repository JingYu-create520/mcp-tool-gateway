package com.example.mcp.tools.vredis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * vredis HTTP Adapter：所有调用失败（连不上/非 2xx）都不抛异常穿透，
 * 统一返回结构化错误 JSON（{"error":"vredis_unavailable",...}），
 * 保证 SafeToolExecutor 链路与审计记录的稳定性。
 */
@Component
public class VredisHttpClient {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(VredisHttpClient.class);
    // 本模块不依赖 gateway-core（依赖方向相反），这里持有一个自己的 mapper 实例
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient restClient;
    private final boolean configured;

    public VredisHttpClient(@Value("${vredis.base-url:http://localhost:9090}") String baseUrl,
                            @Value("${vredis.connect-timeout-ms:3000}") long connectTimeoutMs,
                            @Value("${vredis.request-timeout-ms:5000}") long requestTimeoutMs) {
        this.configured = baseUrl != null && !baseUrl.isBlank();
        // 显式超时：后端卡死不能拖死 MCP 工具线程（与 upstream 侧 TimeLimiter 防护对等）
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(requestTimeoutMs));
        this.restClient = RestClient.builder()
                .baseUrl(configured ? baseUrl : "http://localhost:0")
                .requestFactory(requestFactory)
                .build();
    }

    public String search(String body) {
        return post("/search", body);
    }

    public String upsert(String body) {
        return post("/upsert", body);
    }

    public String delete(String body) {
        return exchange(() -> restClient.method(HttpMethod.DELETE)
                .uri("/delete")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class)
                .getBody());
    }

    public String stats() {
        return exchange(() -> restClient.get()
                .uri("/stats")
                .retrieve()
                .toEntity(String.class)
                .getBody());
    }

    private String post(String path, String body) {
        return exchange(() -> restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class)
                .getBody());
    }

    private String exchange(Supplier<String> call) {
        if (!configured) {
            // 未部署 vredis 是部署形态而非故障：给人话，不发起注定失败的请求
            return unavailable("vredis 后端未配置（vredis.base-url 为空）");
        }
        try {
            String result = call.get();
            return result == null || result.isBlank() ? "{}" : result;
        } catch (RestClientResponseException e) {
            return unavailable("vredis 返回错误: HTTP " + e.getStatusCode().value());
        } catch (Exception e) {
            log.warn("vredis 调用失败: {}", e.getMessage());
            return unavailable("vredis 不可达: " + e.getMessage());
        }
    }

    private static String unavailable(String message) {
        // JsonMapper 序列化：异常消息含引号/换行时也能生成合法 JSON（结果会写入 JSONB 列）
        return JSON.writeValueAsString(Map.of("error", "vredis_unavailable", "message", message));
    }
}
