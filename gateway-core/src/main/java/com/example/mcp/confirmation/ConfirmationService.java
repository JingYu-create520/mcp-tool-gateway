package com.example.mcp.confirmation;

import com.example.mcp.audit.AuditEvent;
import com.example.mcp.audit.AuditEventPublisher;
import com.example.mcp.security.CallerContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 人工确认状态机服务。所有转换只在合法前置状态下生效，
 * 非法前置（如对已 REJECTED 的记录 confirm）不报错、直接返回当前状态 —— 幂等语义。
 */
@Service
public class ConfirmationService {

    /** pending 默认有效期；超时由 ExpirationScheduler 扫描置 EXPIRED。 */
    static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private final PendingExecutionRepository repository;
    private final AuditEventPublisher auditPublisher;

    public ConfirmationService(PendingExecutionRepository repository, AuditEventPublisher auditPublisher) {
        this.repository = repository;
        this.auditPublisher = auditPublisher;
    }

    @Transactional
    public PendingExecution createPending(String toolName, CallerContext caller, String inputJson) {
        PendingExecution pending = new PendingExecution();
        pending.setConfirmationId(UUID.randomUUID());
        pending.setToolName(toolName);
        pending.setCallerId(caller.callerId());
        pending.setTenantId(caller.tenantId() == null ? "" : caller.tenantId());
        pending.setInput(inputJson);
        pending.setInputHash(sha256(inputJson));
        pending.setStatus(ConfirmationStatus.PENDING);
        Instant now = Instant.now();
        pending.setCreatedAt(now);
        pending.setExpiresAt(now.plus(DEFAULT_TTL));
        PendingExecution saved = repository.save(pending);
        audit(saved, "PENDING", null);
        return saved;
    }

    /** 仅 PENDING 时变 CONFIRMED；否则返回当前状态（幂等）。 */
    @Transactional
    public PendingExecution confirm(UUID confirmationId) {
        PendingExecution pending = find(confirmationId);
        if (pending.getStatus() == ConfirmationStatus.PENDING) {
            pending.setStatus(ConfirmationStatus.CONFIRMED);
            pending.setConfirmedAt(Instant.now());
            audit(pending, "CONFIRMED", null);
        }
        return pending;
    }

    /** 仅 PENDING 时变 REJECTED；否则返回当前状态（幂等）。 */
    @Transactional
    public PendingExecution reject(UUID confirmationId) {
        PendingExecution pending = find(confirmationId);
        if (pending.getStatus() == ConfirmationStatus.PENDING) {
            pending.setStatus(ConfirmationStatus.REJECTED);
            audit(pending, "REJECTED", null);
        }
        return pending;
    }

    /** 仅 CONFIRMED 时变 EXECUTED 并记录执行结果；否则返回当前状态。 */
    @Transactional
    public PendingExecution markExecuted(UUID confirmationId, String resultJson) {
        PendingExecution pending = find(confirmationId);
        if (pending.getStatus() == ConfirmationStatus.CONFIRMED) {
            pending.setStatus(ConfirmationStatus.EXECUTED);
            pending.setResult(resultJson);
            pending.setExecutedAt(Instant.now());
            audit(pending, "EXECUTED", resultJson);
        }
        return pending;
    }

    /** 仅 PENDING 时变 EXPIRED；否则返回当前状态。 */
    @Transactional
    public PendingExecution markExpired(UUID confirmationId) {
        PendingExecution pending = find(confirmationId);
        if (pending.getStatus() == ConfirmationStatus.PENDING) {
            pending.setStatus(ConfirmationStatus.EXPIRED);
            audit(pending, "EXPIRED", null);
        }
        return pending;
    }

    public PendingExecution find(UUID confirmationId) {
        return repository.findByConfirmationId(confirmationId)
                .orElseThrow(() -> new IllegalArgumentException("确认记录不存在: " + confirmationId));
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 状态转换审计：在本事务内写 outbox（publisher REQUIRED 传播），
     * 业务变更与审计记录原子提交 —— Outbox 模式"审计不丢"的落点。
     */
    private void audit(PendingExecution pending, String status, String output) {
        AuditEvent event = new AuditEvent();
        event.setCallerId(pending.getCallerId());
        event.setTenantId(pending.getTenantId());
        event.setToolName(pending.getToolName());
        event.setInput(pending.getInput());
        event.setOutput(output);
        event.setStatus(status);
        event.setConfirmationId(pending.getConfirmationId().toString());
        auditPublisher.publish(event);
    }
}
