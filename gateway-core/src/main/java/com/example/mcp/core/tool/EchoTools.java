package com.example.mcp.core.tool;

import com.example.mcp.audit.AuditEvent;
import com.example.mcp.audit.AuditEventPublisher;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * 连通性自检工具：READ 级，走权限直通路径，不进入人工确认状态机。
 * 注解 hints 显式声明只读语义，避免 MCP 默认 destructiveHint=true 的误导。
 * 注解工具不经过 SafeToolExecutor，审计由 ToolCallAuditor 直接发布。
 */
@Component
public class EchoTools {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AuditEventPublisher auditPublisher;
    private final ToolCallAuditor auditor;

    public EchoTools(AuditEventPublisher auditPublisher, ToolCallAuditor auditor) {
        this.auditPublisher = auditPublisher;
        this.auditor = auditor;
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
        return result;
    }
}
