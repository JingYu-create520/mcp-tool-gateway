package com.example.mcp.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * demo agent 的连接配置：gateway.base-url / gateway.api-key（application.yml）。
 */
@ConfigurationProperties(prefix = "gateway")
public record AgentConfig(String baseUrl, String apiKey) {

    public AgentConfig {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8080";
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = "admin-key";
        }
    }
}
