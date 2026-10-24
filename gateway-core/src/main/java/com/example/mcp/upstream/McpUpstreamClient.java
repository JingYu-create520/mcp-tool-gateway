package com.example.mcp.upstream;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.spec.McpSchema;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 到单个 upstream MCP Server 的客户端连接（编程式 SDK 2.0 客户端，与 agent-demo 同款写法）。
 * 懒连接 + 失败重置：调用失败后由 UpstreamToolRouter 调 reset()，下次调用重新 initialize。
 */
public class McpUpstreamClient {

    private final String name;
    private final String baseUrl;
    private final String apiKey;
    private final Object lock = new Object();
    private volatile McpSyncClient client;

    public McpUpstreamClient(String name, String baseUrl, String apiKey) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public String getName() {
        return name;
    }

    public List<McpSchema.Tool> listTools() {
        try {
            return client().listTools().tools();
        } catch (Exception e) {
            // 会话可能已被服务端过期回收：重置后立即重试一次
            reset();
            return client().listTools().tools();
        }
    }

    public McpSchema.CallToolResult callTool(String upstreamToolName, Map<String, Object> args) {
        try {
            return client().callTool(new McpSchema.CallToolRequest(upstreamToolName, args));
        } catch (Exception firstAttempt) {
            // 仅在"请求必然未被执行"的会话失效场景重试（服务端回收会话返回 404 等）：
            // 超时/IO 失败的执行结果未知，盲目重试会对有副作用的 upstream 工具造成重复执行
            if (!isSessionInvalid(firstAttempt)) {
                throw firstAttempt;
            }
            reset();
            return client().callTool(new McpSchema.CallToolRequest(upstreamToolName, args));
        }
    }

    /** 会话失效特征：upstream 明确返回了"没有这个会话/资源"，工具逻辑肯定没跑。 */
    private static boolean isSessionInvalid(Exception e) {
        String message = String.valueOf(e.getMessage()).toLowerCase();
        return message.contains("404") || message.contains("not found") || message.contains("session");
    }

    /** 健康检查：MCP 协议级 ping；失败时重置会话并立即重试一次，仍失败才判定不健康。 */
    public boolean ping() {
        try {
            client().ping();
            return true;
        } catch (Exception e) {
            reset();
            try {
                client().ping();
                return true;
            } catch (Exception retryFailed) {
                return false;
            }
        }
    }

    /** 调用失败后重置缓存会话，下次调用重新连接。 */
    public void reset() {
        synchronized (lock) {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception ignored) {
                    // 会话已死，忽略
                }
                client = null;
            }
        }
    }

    private McpSyncClient client() {
        McpSyncClient current = client;
        if (current == null) {
            synchronized (lock) {
                if (client == null) {
                    client = connect();
                }
                current = client;
            }
        }
        return current;
    }

    private McpSyncClient connect() {
        // base-url 可能带路径（如 http://host:8090/mcp），拆成 base + endpoint
        URI uri = URI.create(baseUrl);
        String base = uri.getScheme() + "://" + uri.getRawAuthority();
        String endpoint = (uri.getRawPath() == null || uri.getRawPath().isBlank()) ? "/mcp" : uri.getRawPath();

        McpSyncHttpClientRequestCustomizer auth = (builder, method, requestUri, endpointUsed, context) -> {
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("X-API-Key", apiKey);
            }
        };

        var transport = HttpClientStreamableHttpTransport.builder(base)
                .endpoint(endpoint)
                .connectTimeout(Duration.ofSeconds(3))
                .httpRequestCustomizer(auth)
                .build();

        McpSyncClient created = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(5))
                .clientInfo(new McpSchema.Implementation("gateway-federation", "1.0.0"))
                .build();
        created.initialize();
        return created;
    }
}
