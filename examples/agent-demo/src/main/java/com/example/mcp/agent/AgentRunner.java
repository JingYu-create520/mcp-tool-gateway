package com.example.mcp.agent;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 可运行的 demo agent：用 MCP Client（Streamable HTTP + X-API-Key）连接本项目的 Gateway，
 * 以一个固定状态机（无 LLM）完成真实任务：
 *
 * <ol>
 *   <li>步骤 1：vredis_search（READ，直通）</li>
 *   <li>步骤 2：vredis_upsert（WRITE）→ 返回 pending + confirmationId</li>
 *   <li>步骤 3：REST 自动确认（模拟人工确认台）</li>
 *   <li>步骤 4：同输入再调用 → executed</li>
 *   <li>步骤 5：查询审计轨迹并打印</li>
 * </ol>
 */
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger("AGENT-DEMO");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /** 一次完整 demo 的结果快照（供测试断言）。 */
    public record DemoResult(
            String searchStatus,
            String upsertFirstStatus,
            String confirmationId,
            String confirmHttpStatus,
            String upsertSecondStatus,
            int auditRows) {
    }

    private final String gatewayBaseUrl;
    private final String apiKey;
    private final RestClient rest;

    public AgentRunner(String gatewayBaseUrl, String apiKey) {
        this.gatewayBaseUrl = gatewayBaseUrl;
        this.apiKey = apiKey;
        this.rest = RestClient.builder()
                .baseUrl(gatewayBaseUrl)
                .defaultHeader("X-API-Key", apiKey)
                .build();
    }

    public DemoResult run() {
        McpSyncClient mcp = connectMcp();
        try {
            // 步骤 1：READ 工具直通执行
            Map<String, Object> search = callTool(mcp, "vredis_search", Map.of("pattern", "agent:*"));
            String searchStatus = (String) search.get("status");
            require(searchStatus, "executed", "步骤1 vredis_search");
            log.info("[步骤1] vredis_search → {}", search);

            // 步骤 2：WRITE 工具 → 网关创建 pending，等人工确认
            Map<String, Object> args = Map.of("key", "agent:demo", "value", "written-by-agent-demo");
            Map<String, Object> firstUpsert = callTool(mcp, "vredis_upsert", args);
            String upsertFirstStatus = (String) firstUpsert.get("status");
            require(upsertFirstStatus, "pending", "步骤2 vredis_upsert");
            String confirmationId = (String) firstUpsert.get("confirmationId");
            log.info("[步骤2] vredis_upsert → pending，confirmationId={}", confirmationId);

            // 步骤 3：自动确认（demo 里代替人工确认台；生产中该凭据应与人隔离）
            ResponseEntity<String> confirmed = rest.post()
                    .uri("/confirmations/{id}/confirm", confirmationId)
                    .retrieve()
                    .toEntity(String.class);
            log.info("[步骤3] confirm {} → HTTP {} {}", confirmationId,
                    confirmed.getStatusCode(), confirmed.getBody());

            // 步骤 4：同输入再调用 → 网关命中已确认记录并执行
            Map<String, Object> secondUpsert = callTool(mcp, "vredis_upsert", args);
            String upsertSecondStatus = (String) secondUpsert.get("status");
            require(upsertSecondStatus, "executed", "步骤4 vredis_upsert(确认后)");
            log.info("[步骤4] vredis_upsert(确认后) → {}", secondUpsert);

            // 步骤 5：查询审计轨迹（Outbox 异步投递有秒级延迟，轮询等待 relay 落库）
            int auditRows = 0;
            String auditBody = "{}";
            for (int attempt = 0; attempt < 12; attempt++) {
                ResponseEntity<String> audit = rest.get()
                        .uri("/admin/audit?toolName=vredis_upsert")
                        .retrieve()
                        .toEntity(String.class);
                auditBody = audit.getBody();
                auditRows = countRows(auditBody);
                if (auditRows >= 3) {
                    break;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.info("[步骤5] 审计轨迹（{} 条）: {}", auditRows, auditBody);

            return new DemoResult(searchStatus, upsertFirstStatus, confirmationId,
                    String.valueOf(confirmed.getStatusCode().value()), upsertSecondStatus, auditRows);
        } finally {
            mcp.close();
        }
    }

    /** MCP Streamable HTTP 客户端：所有请求带 X-API-Key（网关认证必需）。 */
    private McpSyncClient connectMcp() {
        McpSyncHttpClientRequestCustomizer apiKeyHeader = (builder, method, uri, endpoint, context) ->
                builder.header("X-API-Key", apiKey);

        var transport = HttpClientStreamableHttpTransport.builder(gatewayBaseUrl)
                .endpoint("/mcp")
                .httpRequestCustomizer(apiKeyHeader)
                .build();

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .clientInfo(new McpSchema.Implementation("agent-demo", "1.0.0"))
                .build();

        McpSchema.InitializeResult init = client.initialize();
        log.info("[连接] MCP initialize 成功：protocol={}，server={}",
                init.protocolVersion(), init.serverInfo().name());
        return client;
    }

    private Map<String, Object> callTool(McpSyncClient mcp, String toolName, Map<String, Object> args) {
        McpSchema.CallToolResult result = mcp.callTool(new McpSchema.CallToolRequest(toolName, args));
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException("工具调用返回错误: " + render(result.content()));
        }
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        return JSON.readValue(text, MAP_TYPE);
    }

    private void require(String actual, String expected, String step) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(step + " 状态不符：期望 " + expected + "，实际 " + actual);
        }
    }

    private int countRows(String pageJson) {
        Map<String, Object> page = JSON.readValue(pageJson, MAP_TYPE);
        Object total = page.get("totalElements");
        return total instanceof Number number ? number.intValue() : 0;
    }

    private static String render(List<McpSchema.Content> content) {
        return content.stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(c -> ((McpSchema.TextContent) c).text())
                .reduce((a, b) -> a + "; " + b)
                .orElse("{}");
    }
}
