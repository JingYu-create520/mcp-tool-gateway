package com.example.mcp.core.tool;

import tools.jackson.databind.json.JsonMapper;
import com.example.mcp.audit.AuditEvent;
import com.example.mcp.audit.AuditEventPublisher;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 连通性自检工具：READ 级，走权限直通路径，不进入人工确认状态机。
 * 注解 hints 显式声明只读语义，避免 MCP 默认 destructiveHint=true 的误导。
 * 注解工具不经过 SafeToolExecutor，审计与指标由 ToolCallAuditor / ToolMetrics 直接记录。
 */
@Component
public class EchoTools {

    private static final JsonMapper JSON = com.example.mcp.support.JsonMappers.MAPPER;

    private final AuditEventPublisher auditPublisher;
    private final ToolCallAuditor auditor;
    private final com.example.mcp.metrics.ToolMetrics toolMetrics;

    public EchoTools(AuditEventPublisher auditPublisher, ToolCallAuditor auditor,
                     com.example.mcp.metrics.ToolMetrics toolMetrics) {
        this.auditPublisher = auditPublisher;
        this.auditor = auditor;
        this.toolMetrics = toolMetrics;
    }

    @McpTool(
        name = "echo",
        description = "回显收到的消息，作为网关连通性自检工具",
        annotations = @McpTool.McpAnnotations(
            title = "echo",
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false))
    public String echo(@McpToolParam(description = "要回显的消息") String message) {
        long start = System.nanoTime();
        String result = message;
        long durationMs = (System.nanoTime() - start) / 1_000_000;

        AuditEvent event = auditor.event("echo", JSON.writeValueAsString(Map.of("message", message)));
        event.setOutput(result);
        event.setStatus("EXECUTED");
        event.setDurationMs(durationMs);
        auditPublisher.publish(event);

        toolMetrics.recordCall("echo", "READ", "success", durationMs);
        return result;
    }
}
