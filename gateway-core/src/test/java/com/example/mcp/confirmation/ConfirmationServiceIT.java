package com.example.mcp.confirmation;

import com.example.mcp.security.CallerContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 确认状态机单/集成测试：转换合法性 + 幂等语义 + 原子 claim。
 */
@SpringBootTest
@ActiveProfiles("test")
class ConfirmationServiceIT {

    @Autowired
    ConfirmationService service;

    @Autowired
    PendingExecutionRepository repository;

    @Autowired
    com.example.mcp.audit.AuditEventPublisher auditPublisher;

    @Autowired
    com.example.mcp.metrics.ToolMetrics toolMetrics;

    @Autowired
    com.example.mcp.audit.AuditEventRepository auditRepository;

    @Test
    void createThenConfirmThenClaimThenMarkExecuted() {
        PendingExecution pending = createPending();
        assertThat(pending.getStatus()).isEqualTo(ConfirmationStatus.PENDING);

        PendingExecution confirmed = service.confirm(pending.getConfirmationId());
        assertThat(confirmed.getStatus()).isEqualTo(ConfirmationStatus.CONFIRMED);
        assertThat(confirmed.getConfirmedAt()).isNotNull();

        assertThat(repository.transitionStatus(
                pending.getConfirmationId(), ConfirmationStatus.CONFIRMED, ConfirmationStatus.EXECUTING))
                .isEqualTo(1);

        PendingExecution executed = service.markExecuted(pending.getConfirmationId(), "{\"ok\":true}");
        assertThat(executed.getStatus()).isEqualTo(ConfirmationStatus.EXECUTED);
        assertThat(executed.getResult()).isEqualTo("{\"ok\":true}");
        assertThat(executed.getExecutedAt()).isNotNull();
    }

    @Test
    void claimIsAtomic() {
        PendingExecution pending = createPending();
        service.confirm(pending.getConfirmationId());

        assertThat(repository.transitionStatus(
                pending.getConfirmationId(), ConfirmationStatus.CONFIRMED, ConfirmationStatus.EXECUTING))
                .isEqualTo(1);
        // 第二次 claim 抢不到：状态已不是 CONFIRMED（多实例并发时数据库层保证只有一个成功）
        assertThat(repository.transitionStatus(
                pending.getConfirmationId(), ConfirmationStatus.CONFIRMED, ConfirmationStatus.EXECUTING))
                .isEqualTo(0);
    }

    @Test
    void confirmIsIdempotent() {
        PendingExecution pending = createPending();
        service.confirm(pending.getConfirmationId());

        PendingExecution again = service.confirm(pending.getConfirmationId());
        assertThat(again.getStatus()).isEqualTo(ConfirmationStatus.CONFIRMED);
    }

    @Test
    void rejectTransitionsAndIsIdempotent() {
        PendingExecution pending = createPending();
        PendingExecution rejected = service.reject(pending.getConfirmationId());
        assertThat(rejected.getStatus()).isEqualTo(ConfirmationStatus.REJECTED);

        PendingExecution again = service.reject(pending.getConfirmationId());
        assertThat(again.getStatus()).isEqualTo(ConfirmationStatus.REJECTED);

        // 已 REJECTED 的记录 confirm 不改变状态、不报错
        assertThat(service.confirm(pending.getConfirmationId()).getStatus())
                .isEqualTo(ConfirmationStatus.REJECTED);
    }

    @Test
    void markExecutedReplayIsIdempotentNoDoubleAudit() {
        // 幂等重放：EXECUTED 后再次 markExecuted（updated=0）→ 状态不变、不重复写审计
        PendingExecution pending = createPending();
        service.confirm(pending.getConfirmationId());
        repository.transitionStatus(pending.getConfirmationId(),
                ConfirmationStatus.CONFIRMED, ConfirmationStatus.EXECUTING);
        service.markExecuted(pending.getConfirmationId(), "{\"first\":true}");
        long auditCountBefore = countExecutedAudits(pending.getConfirmationId());

        PendingExecution replay = service.markExecuted(pending.getConfirmationId(), "{\"second\":false}");
        assertThat(replay.getStatus()).isEqualTo(ConfirmationStatus.EXECUTED);
        assertThat(replay.getResult()).isEqualTo("{\"first\":true}");
        assertThat(countExecutedAudits(pending.getConfirmationId())).isEqualTo(auditCountBefore);
    }

    private long countExecutedAudits(UUID confirmationId) {
        return auditRepository.findByToolNameAndStatusOrderByCreatedAtDesc("probe_tool", "EXECUTED")
                .stream().filter(e -> confirmationId.toString().equals(e.getConfirmationId())).count();
    }

    @Test
    void markExecutedOnlyFromExecuting() {
        PendingExecution pending = createPending();
        assertThat(service.markExecuted(pending.getConfirmationId(), "{\"x\":1}").getStatus())
                .isEqualTo(ConfirmationStatus.PENDING);

        // 已确认但未 claim：不能直接标记执行
        service.confirm(pending.getConfirmationId());
        assertThat(service.markExecuted(pending.getConfirmationId(), "{\"x\":1}").getStatus())
                .isEqualTo(ConfirmationStatus.CONFIRMED);
    }

    @Test
    void markExpiredOnlyFromUnfinishedStates() {
        // PENDING：超时未确认
        PendingExecution pending = createPending();
        assertThat(service.markExpired(pending.getConfirmationId()).getStatus())
                .isEqualTo(ConfirmationStatus.EXPIRED);

        // CONFIRMED：确认后未在有效期内执行，确认机会作废
        PendingExecution confirmed = createPending();
        service.confirm(confirmed.getConfirmationId());
        assertThat(service.markExpired(confirmed.getConfirmationId()).getStatus())
                .isEqualTo(ConfirmationStatus.EXPIRED);

        // EXECUTED：终态不再翻转
        PendingExecution done = createPending();
        service.confirm(done.getConfirmationId());
        repository.transitionStatus(done.getConfirmationId(),
                ConfirmationStatus.CONFIRMED, ConfirmationStatus.EXECUTING);
        service.markExecuted(done.getConfirmationId(), "{\"ok\":true}");
        assertThat(service.markExpired(done.getConfirmationId()).getStatus())
                .isEqualTo(ConfirmationStatus.EXECUTED);
    }

    @Test
    void findMissingThrows() {
        assertThatThrownBy(() -> service.find(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void confirmingExpiredRecordYieldsExpiredNotFakeSuccess() {
        PendingExecution pending = createPending();
        // 模拟确认有效期已过（sweep 还没扫到的窗口内人工点了确认）
        PendingExecution record = repository.findByConfirmationId(pending.getConfirmationId()).orElseThrow();
        record.setExpiresAt(Instant.now().minusSeconds(1));
        repository.save(record);

        PendingExecution result = service.confirm(pending.getConfirmationId());
        assertThat(result.getStatus()).isEqualTo(ConfirmationStatus.EXPIRED);
    }

    @Test
    void claimRejectsExpiredConfirmation() {
        PendingExecution pending = createPending();
        service.confirm(pending.getConfirmationId());
        // 查询命中与 claim 之间过期：claim 必须在数据库层拒绝（不能执行已作废的确认）
        PendingExecution record = repository.findByConfirmationId(pending.getConfirmationId()).orElseThrow();
        record.setExpiresAt(Instant.now().minusSeconds(1));
        repository.save(record);

        assertThat(repository.claimForExecution(pending.getConfirmationId(),
                ConfirmationStatus.CONFIRMED, ConfirmationStatus.EXECUTING, Instant.now()))
                .isZero();
        assertThat(repository.findByConfirmationId(pending.getConfirmationId()).orElseThrow().getStatus())
                .isEqualTo(ConfirmationStatus.CONFIRMED);
    }

    @Test
    void configuredTtlDefaultsTo30Minutes() {
        // 锚定 YAML/注解默认值的解析：占位符写坏（拼写/缺默认值）时此处变红
        assertThat(service.ttl()).isEqualTo(30);
    }

    @Test
    void customTtlIsHonored() {
        ConfirmationService custom =
                new ConfirmationService(repository, auditPublisher, toolMetrics, java.time.Duration.ofMinutes(5));
        PendingExecution pending = custom.createPending("ttl_probe",
                new CallerContext("ttl-caller", "tenant-a", Set.of("user")), "{}");
        long minutes = java.time.Duration.between(pending.getCreatedAt(), pending.getExpiresAt()).toMinutes();
        assertThat(minutes).isEqualTo(5);
    }

    private PendingExecution createPending() {
        return service.createPending("probe_tool",
                new CallerContext("caller-1", "tenant-a", Set.of("user")), "{\"k\":\"v\"}");
    }
}
