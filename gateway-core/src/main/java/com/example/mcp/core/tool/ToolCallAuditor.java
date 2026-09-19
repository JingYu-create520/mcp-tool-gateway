package com.example.mcp.core.tool;

import com.example.mcp.audit.AuditEvent;
import com.example.mcp.security.CallerContext;
import com.example.mcp.security.PermissionChecker;
import com.example.mcp.security.PermissionDeniedException;
import org.springframework.stereotype.Component;

/**
 * 注解声明的 MCP 工具（echo、get_confirmation_status 等不经过 SafeToolExecutor）的审计入口：
 * 构造带调用方身份的审计事件，工具方法自行测量耗时、填充输出后发布。
 */
@Component
public class ToolCallAuditor {

    private final PermissionChecker permissionChecker;

    public ToolCallAuditor(PermissionChecker permissionChecker) {
        this.permissionChecker = permissionChecker;
    }

    public AuditEvent event(String toolName, String inputJson) {
        AuditEvent event = new AuditEvent();
        try {
            CallerContext caller = permissionChecker.currentCaller();
            event.setCallerId(caller.callerId());
            event.setTenantId(caller.tenantId() == null ? "" : caller.tenantId());
        } catch (PermissionDeniedException e) {
            event.setCallerId("unknown");
            event.setTenantId("");
        }
        event.setToolName(toolName);
        event.setInput(inputJson);
        return event;
    }
}
