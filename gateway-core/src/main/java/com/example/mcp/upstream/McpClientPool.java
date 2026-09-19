package com.example.mcp.upstream;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * upstream 客户端池：启动时按 YAML 配置为每个 enabled 的 upstream 建一个客户端。
 */
@Component
public class McpClientPool {

    private final Map<String, McpUpstreamClient> clients = new LinkedHashMap<>();

    public McpClientPool(UpstreamProperties properties) {
        for (UpstreamProperties.Upstream upstream : properties.upstreams()) {
            if (upstream.isEnabled()) {
                clients.put(upstream.name(),
                        new McpUpstreamClient(upstream.name(), upstream.baseUrl(), upstream.apiKey()));
            }
        }
    }

    public List<McpUpstreamClient> allClients() {
        return List.copyOf(clients.values());
    }

    public McpUpstreamClient byName(String name) {
        return clients.get(name);
    }

    /** 逐个 ping 一遍（重新校验会话有效性；健康结果由 UpstreamHealthChecker 记录）。 */
    public void refreshHealth() {
        for (McpUpstreamClient client : clients.values()) {
            client.ping();
        }
    }
}
