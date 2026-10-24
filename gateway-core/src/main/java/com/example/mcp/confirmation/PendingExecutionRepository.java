package com.example.mcp.confirmation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PendingExecutionRepository extends JpaRepository<PendingExecution, UUID> {

    Optional<PendingExecution> findByConfirmationId(UUID confirmationId);

    /** 过期扫描：PENDING（超时未确认）、CONFIRMED（确认后未执行）与 EXECUTING（执行中断卡死）都按过期清理。 */
    List<PendingExecution> findByStatusInAndExpiresAtBefore(List<ConfirmationStatus> statuses, Instant cutoff);

    /** 确认台列表：按状态集合查询最近的确认单（创建时间倒序，最多 50 条）。 */
    List<PendingExecution> findTop50ByStatusInOrderByCreatedAtDesc(Collection<ConfirmationStatus> statuses);

    /**
     * SafeToolExecutor 查找"同工具 + 同调用方 + 同输入"的已确认待执行记录。
     * 只匹配未过确认有效期的（expiresAt 之后确认作废，重新走确认流程）。
     */
    Optional<PendingExecution> findFirstByToolNameAndCallerIdAndInputHashAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
            String toolName, String callerId, String inputHash, ConfirmationStatus status, Instant now);

    /**
     * 条件状态转换（数据库层原子）：仅当前状态等于 oldStatus 时变更为 newStatus。
     * 返回受影响行数，0 表示当前状态不匹配（如执行失败回退时状态已被并发改动）。
     */
    @Modifying
    @Transactional
    @Query("UPDATE PendingExecution p SET p.status = :newStatus "
            + "WHERE p.confirmationId = :confirmationId AND p.status = :oldStatus")
    int transitionStatus(@Param("confirmationId") UUID confirmationId,
                         @Param("oldStatus") ConfirmationStatus oldStatus,
                         @Param("newStatus") ConfirmationStatus newStatus);

    /**
     * 原子 claim：仅 CONFIRMED 且未过确认有效期时转 EXECUTING。
     * expiresAt 必须在数据库层复查 —— 查询与 claim 之间记录可能恰好过期，
     * 只查 status 会 claim 到已作废的确认，产生"调用方看到执行成功、系统记为 EXPIRED"的矛盾。
     * 返回受影响行数，0 表示状态不匹配或已过期（已被其他实例抢先/确认已作废）。
     */
    @Modifying
    @Transactional
    @Query("UPDATE PendingExecution p SET p.status = :newStatus "
            + "WHERE p.confirmationId = :confirmationId AND p.status = :oldStatus AND p.expiresAt > :now")
    int claimForExecution(@Param("confirmationId") UUID confirmationId,
                          @Param("oldStatus") ConfirmationStatus oldStatus,
                          @Param("newStatus") ConfirmationStatus newStatus,
                          @Param("now") Instant now);

    /**
     * EXECUTING → EXECUTED 的条件转换，同时落执行结果与时间。
     * 用 bulk UPDATE 而非实体更新：请求级持久化上下文（OSIV）里实体可能是
     * claim 前的旧快照，实体状态判断会读到过期值（AgentEndToEndIT 实测踩坑）。
     */
    @Modifying
    @Transactional
    @Query("UPDATE PendingExecution p SET p.status = :newStatus, p.result = :result, p.executedAt = :executedAt "
            + "WHERE p.confirmationId = :confirmationId AND p.status = :oldStatus")
    int transitionToExecuted(@Param("confirmationId") UUID confirmationId,
                             @Param("oldStatus") ConfirmationStatus oldStatus,
                             @Param("newStatus") ConfirmationStatus newStatus,
                             @Param("result") String result,
                             @Param("executedAt") Instant executedAt);

    /**
     * 过期确认单的原子作废：仅 PENDING 且已过确认有效期时转 EXPIRED。
     * 与 confirmForExecution 配对使用（两条 UPDATE 的条件互补且都带 expiresAt 判断），
     * 消除"应用层检查过期 → UPDATE"之间的 check-then-act 窗口。
     */
    @Modifying
    @Transactional
    @Query("UPDATE PendingExecution p SET p.status = :newStatus "
            + "WHERE p.confirmationId = :confirmationId AND p.status = :oldStatus AND p.expiresAt <= :now")
    int expireIfOverdue(@Param("confirmationId") UUID confirmationId,
                        @Param("oldStatus") ConfirmationStatus oldStatus,
                        @Param("newStatus") ConfirmationStatus newStatus,
                        @Param("now") Instant now);

    /** PENDING → CONFIRMED，同时落确认时间（仅未过期记录，数据库层原子）。 */
    @Modifying
    @Transactional
    @Query("UPDATE PendingExecution p SET p.status = :newStatus, p.confirmedAt = :confirmedAt "
            + "WHERE p.confirmationId = :confirmationId AND p.status = :oldStatus AND p.expiresAt > :now")
    int confirmForExecution(@Param("confirmationId") UUID confirmationId,
                            @Param("oldStatus") ConfirmationStatus oldStatus,
                            @Param("newStatus") ConfirmationStatus newStatus,
                            @Param("confirmedAt") Instant confirmedAt,
                            @Param("now") Instant now);

    /** 未完成态（PENDING/CONFIRMED/EXECUTING）→ EXPIRED 的条件转换。 */
    @Modifying
    @Transactional
    @Query("UPDATE PendingExecution p SET p.status = :newStatus "
            + "WHERE p.confirmationId = :confirmationId AND p.status IN :fromStatuses")
    int expireFrom(@Param("confirmationId") UUID confirmationId,
                   @Param("fromStatuses") Collection<ConfirmationStatus> fromStatuses,
                   @Param("newStatus") ConfirmationStatus newStatus);
}
