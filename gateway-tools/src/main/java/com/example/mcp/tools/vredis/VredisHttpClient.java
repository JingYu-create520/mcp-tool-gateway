package com.example.mcp.tools.vredis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

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

    private final RestClient restClient;

    public VredisHttpClient(@Value("${vredis.base-url:http://localhost:9090}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
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
        return "{\"error\":\"vredis_unavailable\",\"message\":\""
                + message.replace("\\", "/").replace("\"", "'") + "\"}";
    }
}
