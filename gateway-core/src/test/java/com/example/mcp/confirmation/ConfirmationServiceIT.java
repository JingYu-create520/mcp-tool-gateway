package com.example.mcp.confirmation;

import com.example.mcp.security.CallerContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 确认状态机单/集成测试：转换合法性 + 幂等语义。
 */
@SpringBootTest
@ActiveProfiles("test")
class ConfirmationServiceIT {

    @Autowired
    ConfirmationService service;

    @Test
    void createThenConfirmThenMarkExecuted() {
        PendingExecution pending = createPending();
        assertThat(pending.getStatus()).isEqualTo(ConfirmationStatus.PENDING);

        PendingExecution confirmed = service.confirm(pending.getConfirmationId());
        assertThat(confirmed.getStatus()).isEqualTo(ConfirmationStatus.CONFIRMED);
        assertThat(confirmed.getConfirmedAt()).isNotNull();

        PendingExecution executed = service.markExecuted(pending.getConfirmationId(), "{\"ok\":true}");
        assertThat(executed.getStatus()).isEqualTo(ConfirmationStatus.EXECUTED);
        assertThat(executed.getResult()).isEqualTo("{\"ok\":true}");
        assertThat(executed.getExecutedAt()).isNotNull();
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
    void markExecutedOnlyFromConfirmed() {
        PendingExecution pending = createPending();
        assertThat(service.markExecuted(pending.getConfirmationId(), "{\"x\":1}").getStatus())
                .isEqualTo(ConfirmationStatus.PENDING);
    }

    @Test
    void markExpiredOnlyPending() {
        PendingExecution pending = createPending();
        assertThat(service.markExpired(pending.getConfirmationId()).getStatus())
                .isEqualTo(ConfirmationStatus.EXPIRED);

        PendingExecution confirmed = createPending();
        service.confirm(confirmed.getConfirmationId());
        assertThat(service.markExpired(confirmed.getConfirmationId()).getStatus())
                .isEqualTo(ConfirmationStatus.CONFIRMED);
    }

    @Test
    void findMissingThrows() {
        assertThatThrownBy(() -> service.find(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private PendingExecution createPending() {
        return service.createPending("probe_tool",
                new CallerContext("caller-1", "tenant-a", Set.of("user")), "{\"k\":\"v\"}");
    }
}
