package com.example.mcp.confirmation;

import com.example.mcp.audit.AuditEvent;
import com.example.mcp.audit.AuditEventPublisher;
import com.example.mcp.metrics.ToolMetrics;
import com.example.mcp.security.CallerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 人工确认状态机服务。所有转换只在合法前置状态下生效，
 * 非法前置（如对已 REJECTED 的记录 confirm）不报错、直接返回当前状态 —— 幂等语义。
 */
@Service
public class ConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationService.class);

    /** pending 默认有效期（gateway.confirmation-ttl-minutes 可覆盖）；超时由 ExpirationScheduler 扫描置 EXPIRED。 */
    static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private final PendingExecutionRepository repository;
    private final AuditEventPublisher auditPublisher;
    private final ToolMetrics toolMetrics;
    private final Duration ttl;

    // 类有多个构造器时 Spring 需要唯一标注的注入入口
    @org.springframework.beans.factory.annotation.Autowired
    public ConfirmationService(PendingExecutionRepository repository, AuditEventPublisher auditPublisher,
                               ToolMetrics toolMetrics,
                               @org.springframework.beans.factory.annotation.Value("${gateway.confirmation-ttl-minutes:30}") long ttlMinutes) {
        this(repository, auditPublisher, toolMetrics, Duration.ofMinutes(ttlMinutes));
    }

    /** 测试用：直接注入 TTL。 */
    ConfirmationService(PendingExecutionRepository repository, AuditEventPublisher auditPublisher,
                        ToolMetrics toolMetrics, Duration ttl) {
        this.repository = repository;
        this.auditPublisher = auditPublisher;
        this.toolMetrics = toolMetrics;
        this.ttl = ttl;
    }

    /** 供配置锚定测试读取实际生效的 TTL。 */
    long ttl() {
        return ttl.toMinutes();
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
        pending.setExpiresAt(now.plus(ttl));
        PendingExecution saved = repository.save(pending);
        audit(saved, "PENDING", null);
        return saved;
    }

    /**
     * 仅 PENDING 时变 CONFIRMED；否则返回当前状态（幂等）。
     * 过期判断在 UPDATE 的 WHERE 里原子完成（与 claimForExecution 同款不变量）：
     * 已过确认有效期的记录 confirm 会得到 EXPIRED 而非假成功，且不存在
     * "应用层检查过期 → UPDATE"之间的 check-then-act 窗口。
     */
    @Transactional
    public PendingExecution confirm(UUID confirmationId) {
        PendingExecution pending = find(confirmationId);
        Instant now = Instant.now();
        // 1) 已过期的 PENDING：确认动作合法但机会已作废 → 落 EXPIRED（与过期扫描同款审计）
        int expired = repository.expireIfOverdue(
                confirmationId, ConfirmationStatus.PENDING, ConfirmationStatus.EXPIRED, now);
        if (expired > 0) {
            pending.setStatus(ConfirmationStatus.EXPIRED);
            audit(pending, "EXPIRED", null);
            return pending;
        }
        // 2) 未过期的 PENDING：正常确认（expiresAt > now 条件覆盖上一步之后的时钟推进）
        int updated = repository.confirmForExecution(
                confirmationId, ConfirmationStatus.PENDING, ConfirmationStatus.CONFIRMED, now, now);
        if (updated > 0) {
            pending.setStatus(ConfirmationStatus.CONFIRMED);
            pending.setConfirmedAt(now);
            toolMetrics.recordPendingConfirmed(pending.getCreatedAt());
            audit(pending, "CONFIRMED", null);
        }
        // 两条都 0 行 = 状态不是 PENDING（已确认/拒绝/执行/过期），幂等返回当前快照
        return pending;
    }

    /** 仅 PENDING 时变 REJECTED；否则返回当前状态（幂等）。 */
    @Transactional
    public PendingExecution reject(UUID confirmationId) {
        PendingExecution pending = find(confirmationId);
        int updated = repository.transitionStatus(
                confirmationId, ConfirmationStatus.PENDING, ConfirmationStatus.REJECTED);
        if (updated > 0) {
            pending.setStatus(ConfirmationStatus.REJECTED);
            audit(pending, "REJECTED", null);
        }
        return pending;
    }

    /**
     * 仅 EXECUTING 时变 EXECUTED 并记录执行结果；否则返回当前状态。
     * 前置是 EXECUTING 而非 CONFIRMED：执行方必须先原子 claim（CONFIRMED → EXECUTING），
     * 保证同一条确认记录不会被并发请求执行两次。
     * 转换用条件 UPDATE（数据库层原子），影响行数决定是否写审计 —— 不依赖可能过期的实体快照。
     */
    @Transactional
    public PendingExecution markExecuted(UUID confirmationId, String resultJson) {
        Instant now = Instant.now();
        int updated = repository.transitionToExecuted(
                confirmationId, ConfirmationStatus.EXECUTING, ConfirmationStatus.EXECUTED, resultJson, now);
        PendingExecution pending = find(confirmationId);
        if (updated > 0) {
            // bulk UPDATE 不经过持久化上下文，内存快照需要显式同步
            pending.setStatus(ConfirmationStatus.EXECUTED);
            pending.setResult(resultJson);
            pending.setExecutedAt(now);
            audit(pending, "EXECUTED", resultJson);
        } else {
            // 正常流程到不了这里（claim 成功后立即执行）；出现即状态被并发/过期扫描改写，必须留痕排查
            log.warn("markExecuted 未生效，确认单状态已漂移: confirmationId={}, 当前状态={}",
                    confirmationId, pending.getStatus());
        }
        return pending;
    }

    /**
     * 仅 PENDING（超时未确认）、CONFIRMED（确认后未及时执行）或 EXECUTING（执行中断且超时，
     * 如进程崩溃）时变 EXPIRED；否则返回当前状态。确认有效期过后确认机会作废，
     * 需重新走确认流程；EXECUTING 卡死不退回 CONFIRMED —— 执行是否产生过副作用未知，
     * 重新确认最安全。条件 UPDATE 原子转换，与 bulk claim/executed 路径无乐观锁竞争。
     */
    @Transactional
    public PendingExecution markExpired(UUID confirmationId) {
        PendingExecution pending = find(confirmationId);
        expireTo(pending, List.of(ConfirmationStatus.PENDING, ConfirmationStatus.CONFIRMED,
                ConfirmationStatus.EXECUTING));
        return pending;
    }

    /** 过期转换：条件 UPDATE 到 EXPIRED，成功时写审计并同步调用方的内存快照。 */
    private void expireTo(PendingExecution staleSnapshot, List<ConfirmationStatus> fromStatuses) {
        int updated = repository.expireFrom(
                staleSnapshot.getConfirmationId(), fromStatuses, ConfirmationStatus.EXPIRED);
        if (updated > 0) {
            staleSnapshot.setStatus(ConfirmationStatus.EXPIRED);
            audit(staleSnapshot, "EXPIRED", null);
        }
    }

    public PendingExecution find(UUID confirmationId) {
        return repository.findByConfirmationId(confirmationId)
                .orElseThrow(() -> new NotFoundException("确认记录不存在: " + confirmationId));
    }

    /** 确认台列表：按状态集合查最近的确认单（创建时间倒序，最多 50 条）。 */
    public List<PendingExecution> list(Set<ConfirmationStatus> statuses) {
        return repository.findTop50ByStatusInOrderByCreatedAtDesc(statuses);
    }

    /** 确认单不存在（Controller 层映射 404，与参数错误的 400 区分）。 */
    public static class NotFoundException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public NotFoundException(String message) {
            super(message);
        }
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
