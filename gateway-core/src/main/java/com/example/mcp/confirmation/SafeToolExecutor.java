package com.example.mcp.confirmation;

import com.example.mcp.audit.AuditEvent;
import com.example.mcp.audit.AuditEventPublisher;
import com.example.mcp.registry.ToolRegistry;
import com.example.mcp.registry.ToolRegistryService;
import com.example.mcp.security.CallerContext;
import com.example.mcp.security.PermissionChecker;
import com.example.mcp.security.PermissionDeniedException;
import com.example.mcp.security.ToolAction;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 安全策略执行层：所有数据库注册工具的统一执行入口（MCP 回调包一层）。
 *
 * <ul>
 *   <li>READ —— 权限校验后直通执行；</li>
 *   <li>WRITE / DANGEROUS —— 先查是否存在"同工具 + 同调用方 + 同输入"的 CONFIRMED 记录：
 *       有则【用确认时保存的输入】执行并落 EXECUTED；无则创建 PENDING 等待人工确认。</li>
 * </ul>
 *
 * <p>不使用 MRTR / InputRequiredResult / elicitation（MCP Java SDK 2.0.0 不支持重放），
 * 一律 pending + 客户端轮询（get_confirmation_status / REST 查询）。
 */
@Service
public class SafeToolExecutor {

    private final ToolRegistryService toolRegistryService;
    private final PermissionChecker permissionChecker;
    private final ConfirmationService confirmationService;
    private final PendingExecutionRepository pendingRepository;
    private final AuditEventPublisher auditPublisher;

    public SafeToolExecutor(ToolRegistryService toolRegistryService,
                            PermissionChecker permissionChecker,
                            ConfirmationService confirmationService,
                            PendingExecutionRepository pendingRepository,
                            AuditEventPublisher auditPublisher) {
        this.toolRegistryService = toolRegistryService;
        this.permissionChecker = permissionChecker;
        this.confirmationService = confirmationService;
        this.pendingRepository = pendingRepository;
        this.auditPublisher = auditPublisher;
    }

    /**
     * @param actualExecutor 真实工具实现（阶段五接入 HTTP Adapter），入参/返回值均为 JSON 字符串
     * @return {status: executed|pending, confirmationId?, result?}
     */
    public Map<String, Object> execute(String toolName, String inputJson, Function<String, String> actualExecutor) {
        ToolRegistry tool = toolRegistryService.findByName(toolName)
                .orElseThrow(() -> new IllegalArgumentException("工具不存在: " + toolName));
        CallerContext caller;
        try {
            caller = permissionChecker.check(tool);
        } catch (PermissionDeniedException e) {
            AuditEvent denied = baseEvent(toolName, safeCaller(), inputJson);
            denied.setStatus("DENIED");
            auditPublisher.publish(denied);
            throw e;
        }

        if (tool.getSideEffect() == ToolAction.READ) {
            long start = System.nanoTime();
            String result = actualExecutor.apply(inputJson);
            long durationMs = (System.nanoTime() - start) / 1_000_000;

            AuditEvent executed = baseEvent(toolName, caller, inputJson);
            executed.setOutput(result);
            executed.setStatus("EXECUTED");
            executed.setDurationMs(durationMs);
            auditPublisher.publish(executed);

            return Map.of("status", "executed", "result", result);
        }

        String inputHash = ConfirmationService.sha256(inputJson);
        Optional<PendingExecution> confirmed = pendingRepository
                .findFirstByToolNameAndCallerIdAndInputHashAndStatusOrderByCreatedAtDesc(
                        toolName, caller.callerId(), inputHash, ConfirmationStatus.CONFIRMED);
        if (confirmed.isPresent()) {
            PendingExecution approved = confirmed.get();
            // 安全关键：执行【人工确认时保存的输入】，不是本次调用的输入（防确认后换参攻击）
            String result = actualExecutor.apply(approved.getInput());
            confirmationService.markExecuted(approved.getConfirmationId(), result);
            return Map.of(
                    "status", "executed",
                    "confirmationId", approved.getConfirmationId().toString(),
                    "result", result);
        }

        PendingExecution pending = confirmationService.createPending(toolName, caller, inputJson);
        return Map.of(
                "status", "pending",
                "confirmationId", pending.getConfirmationId().toString(),
                "toolName", toolName,
                "expiresAt", pending.getExpiresAt().toString());
    }

    /** WRITE/DANGEROUS 的 PENDING 审计由 ConfirmationService.createPending 在其事务内发布（原子）。 */
    private CallerContext safeCaller() {
        try {
            return permissionChecker.currentCaller();
        } catch (PermissionDeniedException e) {
            return null;
        }
    }

    private AuditEvent baseEvent(String toolName, CallerContext caller, String inputJson) {
        AuditEvent event = new AuditEvent();
        event.setCallerId(caller == null ? "unknown" : caller.callerId());
        event.setTenantId(caller == null || caller.tenantId() == null ? "" : caller.tenantId());
        event.setToolName(toolName);
        event.setInput(inputJson);
        return event;
    }
}
