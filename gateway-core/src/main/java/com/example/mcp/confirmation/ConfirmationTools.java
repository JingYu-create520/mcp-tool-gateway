package com.example.mcp.confirmation;

import com.example.mcp.audit.AuditEvent;
import com.example.mcp.audit.AuditEventPublisher;
import com.example.mcp.core.tool.ToolCallAuditor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

/**
 * 确认状态查询的 MCP 入口：AI 客户端用 pending + 轮询等待人工结果
 * （SDK 2.0.0 无 InputRequiredResult 重放，见 docs/architecture.md）。
 * 注解工具不经过 SafeToolExecutor，审计由 ToolCallAuditor 直接发布。
 */
@Component
public class ConfirmationTools {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ConfirmationService confirmationService;
    private final AuditEventPublisher auditPublisher;
    private final ToolCallAuditor auditor;

    public ConfirmationTools(ConfirmationService confirmationService,
                             AuditEventPublisher auditPublisher,
                             ToolCallAuditor auditor) {
        this.confirmationService = confirmationService;
        this.auditPublisher = auditPublisher;
        this.auditor = auditor;
    }

    @McpTool(
        name = "get_confirmation_status",
        description = "Get the status of a pending confirmation",
        annotations = @McpTool.McpAnnotations(
            title = "get_confirmation_status",
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false))
    public String getStatus(@McpToolParam(description = "Confirmation ID") String confirmationId) {
        long start = System.nanoTime();
        String status = confirmationService.find(UUID.fromString(confirmationId)).getStatus().name();
        long durationMs = (System.nanoTime() - start) / 1_000_000;

        AuditEvent event = auditor.event("get_confirmation_status",
                JSON.writeValueAsString(Map.of("confirmationId", confirmationId)));
        event.setOutput(status);
        event.setStatus("EXECUTED");
        event.setDurationMs(durationMs);
        auditPublisher.publish(event);
        return status;
    }
}
