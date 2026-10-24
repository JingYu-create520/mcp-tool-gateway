package com.example.mcp.confirmation;

import tools.jackson.databind.json.JsonMapper;
import com.example.mcp.audit.AuditEvent;
import com.example.mcp.audit.AuditEventPublisher;
import com.example.mcp.core.tool.ToolCallAuditor;
import com.example.mcp.metrics.ToolMetrics;
import com.example.mcp.security.CallerContext;
import com.example.mcp.security.PermissionChecker;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 确认状态查询的 MCP 入口：AI 客户端用 pending + 轮询等待人工结果
 * （SDK 2.0.0 无 InputRequiredResult 重放，见 docs/architecture.md）。
 * 只允许查询本人（同 callerId）的确认单；他人的确认单一律按不存在处理（防枚举）。
 * 注解工具不经过 SafeToolExecutor，审计与指标直接记录。
 */
@Component
public class ConfirmationTools {

    private static final JsonMapper JSON = com.example.mcp.support.JsonMappers.MAPPER;

    private final ConfirmationService confirmationService;
    private final AuditEventPublisher auditPublisher;
    private final ToolCallAuditor auditor;
    private final ToolMetrics toolMetrics;
    private final PermissionChecker permissionChecker;

    public ConfirmationTools(ConfirmationService confirmationService,
                             AuditEventPublisher auditPublisher,
                             ToolCallAuditor auditor,
                             ToolMetrics toolMetrics,
                             PermissionChecker permissionChecker) {
        this.confirmationService = confirmationService;
        this.auditPublisher = auditPublisher;
        this.auditor = auditor;
        this.toolMetrics = toolMetrics;
        this.permissionChecker = permissionChecker;
    }

    @McpTool(
        name = "get_confirmation_status",
        description = "Get the status of your own pending confirmation",
        annotations = @McpTool.McpAnnotations(
            title = "get_confirmation_status",
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false))
    public String getStatus(@McpToolParam(description = "Confirmation ID") String confirmationId) {
        long start = System.nanoTime();
        CallerContext caller = permissionChecker.currentCaller();
        PendingExecution pending = confirmationService.find(UUID.fromString(confirmationId));
        // 确认单与创建它的调用方绑定；跨调用方查询按不存在处理，不给探测空间
        if (!pending.getCallerId().equals(caller.callerId())) {
            throw new IllegalArgumentException("确认记录不存在: " + confirmationId);
        }
        String status = pending.getStatus().name();
        long durationMs = (System.nanoTime() - start) / 1_000_000;

        AuditEvent event = auditor.event("get_confirmation_status",
                JSON.writeValueAsString(Map.of("confirmationId", confirmationId)));
        event.setOutput(status);
        event.setStatus("EXECUTED");
        event.setDurationMs(durationMs);
        auditPublisher.publish(event);

        toolMetrics.recordCall("get_confirmation_status", "READ", "success", durationMs);
        return status;
    }
}
