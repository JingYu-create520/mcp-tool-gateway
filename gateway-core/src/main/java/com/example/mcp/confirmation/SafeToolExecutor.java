package com.example.mcp.confirmation;

import com.example.mcp.audit.AuditEvent;
import com.example.mcp.audit.AuditEventPublisher;
import com.example.mcp.metrics.ToolMetrics;
import com.example.mcp.ratelimit.RateLimiter;
import com.example.mcp.ratelimit.ToolRateLimitException;
import com.example.mcp.registry.ToolRegistry;
import com.example.mcp.registry.ToolRegistryService;
import com.example.mcp.security.CallerContext;
import com.example.mcp.security.PermissionChecker;
import com.example.mcp.security.PermissionDeniedException;
import com.example.mcp.security.ToolAction;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
    private final ToolMetrics toolMetrics;
    private final RateLimiter rateLimiter;

    public SafeToolExecutor(ToolRegistryService toolRegistryService,
                            PermissionChecker permissionChecker,
                            ConfirmationService confirmationService,
                            PendingExecutionRepository pendingRepository,
                            AuditEventPublisher auditPublisher,
                            ToolMetrics toolMetrics,
                            RateLimiter rateLimiter) {
        this.toolRegistryService = toolRegistryService;
        this.permissionChecker = permissionChecker;
        this.confirmationService = confirmationService;
        this.pendingRepository = pendingRepository;
        this.auditPublisher = auditPublisher;
        this.toolMetrics = toolMetrics;
        this.rateLimiter = rateLimiter;
    }

    /**
     * @param actualExecutor 真实工具实现（阶段五接入 HTTP Adapter），入参/返回值均为 JSON 字符串
     * @return {status: executed|pending, confirmationId?, result?}
     */
    public Map<String, Object> execute(String toolName, String inputJson, Function<String, String> actualExecutor) {
        ToolRegistry tool = toolRegistryService.findByName(toolName)
                .orElseThrow(() -> new IllegalArgumentException("工具不存在: " + toolName));
        // 管理端 disable 到 MCP removeTool 之间存在极小窗口，执行入口必须自查 enabled
        if (!tool.isEnabled()) {
            throw new IllegalArgumentException("工具不存在: " + toolName);
        }
        CallerContext caller;
        try {
            caller = permissionChecker.check(tool);
        } catch (PermissionDeniedException e) {
            toolMetrics.recordCall(toolName, tool.getSideEffect().name(), "permission_denied", null);
            AuditEvent denied = baseEvent(toolName, safeCaller(), inputJson);
            denied.setStatus("DENIED");
            auditPublisher.publish(denied);
            throw e;
        }

        // rateLimit 为 null/0 = 不限流，在实现内统一处理；调用方只透传注册值
        if (!rateLimiter.tryAcquire(toolName, caller.callerId(),
                tool.getRateLimit() == null ? 0 : tool.getRateLimit())) {
            toolMetrics.recordCall(toolName, tool.getSideEffect().name(), "rate_limited", null);
            AuditEvent limited = baseEvent(toolName, caller, inputJson);
            limited.setStatus("RATE_LIMITED");
            auditPublisher.publish(limited);
            throw new ToolRateLimitException(toolName, tool.getRateLimit());
        }

        if (tool.getSideEffect() == ToolAction.READ) {
            long start = System.nanoTime();
            String result;
            try {
                result = actualExecutor.apply(inputJson);
            } catch (RuntimeException e) {
                toolMetrics.recordCall(toolName, tool.getSideEffect().name(), "error", elapsedMs(start));
                throw e;
            }
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            toolMetrics.recordCall(toolName, tool.getSideEffect().name(), "success", durationMs);

            AuditEvent executed = baseEvent(toolName, caller, inputJson);
            executed.setOutput(result);
            executed.setStatus("EXECUTED");
            executed.setDurationMs(durationMs);
            auditPublisher.publish(executed);

            return Map.of("status", "executed", "result", result);
        }

        String inputHash = ConfirmationService.sha256(inputJson);
        Optional<PendingExecution> confirmed = pendingRepository
                .findFirstByToolNameAndCallerIdAndInputHashAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                        toolName, caller.callerId(), inputHash, ConfirmationStatus.CONFIRMED, Instant.now());
        if (confirmed.isPresent()) {
            return executeConfirmed(tool, caller, inputJson, confirmed.get(), actualExecutor);
        }

        PendingExecution pending = confirmationService.createPending(toolName, caller, inputJson);
        toolMetrics.recordCall(toolName, tool.getSideEffect().name(), "pending", null);
        return Map.of(
                "status", "pending",
                "confirmationId", pending.getConfirmationId().toString(),
                "toolName", toolName,
                "expiresAt", pending.getExpiresAt().toString());
    }

    /**
     * 执行已确认的记录。先原子 claim（CONFIRMED → EXECUTING，数据库层同时复查确认有效期）：
     * 并发拿到同一条 CONFIRMED 记录的请求只有一个能 claim 成功，其余落回创建新 PENDING。
     * 执行失败退回 CONFIRMED（副作用是否发生未知时由工具实现保证幂等），确认机会保留给下次调用，
     * 并落 EXECUTION_FAILED 审计 —— 失败原因与回退在证据链上必须可见。
     */
    private Map<String, Object> executeConfirmed(ToolRegistry tool, CallerContext caller, String inputJson,
                                                 PendingExecution approved,
                                                 Function<String, String> actualExecutor) {
        String toolName = tool.getName();
        UUID confirmationId = approved.getConfirmationId();
        if (pendingRepository.claimForExecution(
                confirmationId, ConfirmationStatus.CONFIRMED, ConfirmationStatus.EXECUTING,
                Instant.now()) == 0) {
            PendingExecution pending = confirmationService.createPending(toolName, caller, inputJson);
            toolMetrics.recordCall(toolName, tool.getSideEffect().name(), "pending", null);
            return Map.of(
                    "status", "pending",
                    "confirmationId", pending.getConfirmationId().toString(),
                    "toolName", toolName,
                    "expiresAt", pending.getExpiresAt().toString());
        }

        // claim 成功即开始执行：落 EXECUTING 审计，执行窗口在证据链上不留空白
        AuditEvent executing = baseEvent(toolName, caller, inputJson);
        executing.setStatus("EXECUTING");
        executing.setConfirmationId(confirmationId.toString());
        auditPublisher.publish(executing);

        long start = System.nanoTime();
        String result;
        try {
            result = actualExecutor.apply(approved.getInput());
        } catch (RuntimeException e) {
            pendingRepository.transitionStatus(
                    confirmationId, ConfirmationStatus.EXECUTING, ConfirmationStatus.CONFIRMED);
            toolMetrics.recordCall(toolName, tool.getSideEffect().name(), "error", elapsedMs(start));
            AuditEvent failed = baseEvent(toolName, caller, inputJson);
            failed.setStatus("EXECUTION_FAILED");
            failed.setOutput(String.valueOf(e.getMessage()));
            failed.setConfirmationId(confirmationId.toString());
            auditPublisher.publish(failed);
            throw e;
        }
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        toolMetrics.recordCall(toolName, tool.getSideEffect().name(), "success", durationMs);
        confirmationService.markExecuted(confirmationId, result);
        return Map.of(
                "status", "executed",
                "confirmationId", confirmationId.toString(),
                "result", result);
    }

    private CallerContext safeCaller() {
        try {
            return permissionChecker.currentCaller();
        } catch (PermissionDeniedException e) {
            return null;
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
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
