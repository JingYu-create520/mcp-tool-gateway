package com.example.mcp.registry;

import com.example.mcp.confirmation.SafeToolExecutor;
import com.example.mcp.security.PermissionDeniedException;
import com.example.mcp.security.ToolAction;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 动态工具注册框架：启动时（在外部工具引导之后）把 tool_registry 中 enabled=true 的工具
 * 注册进 MCP Server；管理端注册/更新/删除后通过 upsert/remove 同步，并向已连接客户端
 * 发送 list_changed 通知。
 *
 * <p>执行器绑定：各集成模块通过 {@link #registerExecutor(String, Function)} 绑定真实
 * 执行器（如 vredis 的 HTTP Adapter）；未绑定的工具回退到内置桩实现。
 * 所有回调统一经 {@link SafeToolExecutor}（权限检查 → READ 直通 / WRITE·DANGEROUS 人工确认）。
 */
@Component
@Order(10)
public class DynamicToolRegistry implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DynamicToolRegistry.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ToolRegistryRepository repository;
    private final McpSyncServer mcpSyncServer;
    private final SafeToolExecutor safeToolExecutor;
    private final Map<String, Function<String, String>> executors = new ConcurrentHashMap<>();

    public DynamicToolRegistry(ToolRegistryRepository repository, McpSyncServer mcpSyncServer,
                               SafeToolExecutor safeToolExecutor) {
        this.repository = repository;
        this.mcpSyncServer = mcpSyncServer;
        this.safeToolExecutor = safeToolExecutor;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<ToolRegistry> enabled = repository.findByEnabledTrue();
        enabled.forEach(this::registerInMcp);
        log.info("动态工具注册完成，共注册 {} 个数据库工具", enabled.size());
    }

    /** 绑定工具的真实执行器（由 ExternalToolRegistrar 在本类之前调用）。 */
    public void registerExecutor(String toolName, Function<String, String> executor) {
        if (executor != null) {
            executors.put(toolName, executor);
        }
    }

    public void upsert(ToolRegistry tool) {
        unregister(tool.getName());
        if (tool.isEnabled()) {
            registerInMcp(tool);
        }
    }

    public void remove(String name) {
        unregister(name);
    }

    private void registerInMcp(ToolRegistry tool) {
        // ToolAction → MCP 注解 hints：READ 只读直通；WRITE 可变但非破坏；DANGEROUS 破坏性
        boolean readOnly = tool.getSideEffect() == ToolAction.READ;
        boolean destructive = tool.getSideEffect() == ToolAction.DANGEROUS;
        McpSchema.ToolAnnotations annotations = McpSchema.ToolAnnotations.builder()
                .title(tool.getName())
                .readOnlyHint(readOnly)
                .destructiveHint(destructive)
                .idempotentHint(readOnly)
                .openWorldHint(true)
                .build();

        McpSchema.Tool mcpTool = McpSchema.Tool
                .builder(tool.getName(), new JacksonMcpJsonMapper(JSON), tool.getInputSchema())
                .description(tool.getDescription())
                .annotations(annotations)
                .build();

        McpServerFeatures.SyncToolSpecification specification = McpServerFeatures.SyncToolSpecification.builder()
                .tool(mcpTool)
                .callHandler((exchange, request) -> {
                    try {
                        String inputJson = JSON.writeValueAsString(request.arguments());
                        Map<String, Object> outcome = safeToolExecutor.execute(
                                tool.getName(), inputJson, executorFor(tool));
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(JSON.writeValueAsString(outcome))
                                .isError(false)
                                .build();
                    } catch (PermissionDeniedException e) {
                        // MCP 语义：权限拒绝是工具级错误结果（isError=true），不是 HTTP 状态码
                        return McpSchema.CallToolResult.builder()
                                .addTextContent("permission_denied: " + e.getMessage())
                                .isError(true)
                                .build();
                    } catch (IllegalArgumentException e) {
                        return McpSchema.CallToolResult.builder()
                                .addTextContent("invalid_request: " + e.getMessage())
                                .isError(true)
                                .build();
                    }
                })
                .build();

        mcpSyncServer.addTool(specification);
        log.info("已注册动态工具 '{}'（{} 级）", tool.getName(), tool.getSideEffect());
    }

    /** 已绑定的真实执行器优先；未绑定的内置工具回退到桩实现（返回合法 JSON）。 */
    private Function<String, String> executorFor(ToolRegistry tool) {
        return executors.getOrDefault(tool.getName(), inputJson -> JSON.writeValueAsString(Map.of(
                "stub", "未绑定真实执行器的内置桩实现",
                "tool", tool.getName(),
                "sideEffect", tool.getSideEffect().name(),
                "input", inputJson)));
    }

    private void unregister(String name) {
        try {
            mcpSyncServer.removeTool(name);
        } catch (Exception e) {
            // 工具尚未注册时忽略（首次注册/幂等 upsert 场景）
        }
    }
}
