package com.example.mcp.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Outbox 发布语义验证：PENDING 落库、payload 含脱敏后事件、
 * 与调用方事务同生共死（业务回滚 → outbox 一并回滚，不会多出孤儿审计）。
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditEventPublisherIT {

    @Autowired
    AuditEventPublisher publisher;

    @Autowired
    OutboxMessageRepository outboxRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void publishWritesPendingOutboxRow() {
        AuditEvent event = sample("pub_probe_1");
        publisher.publish(event);

        OutboxMessage message = findByAggregateId(event.getId().toString());
        assertThat(message).isNotNull();
        assertThat(message.getStatus()).isEqualTo(OutboxMessage.STATUS_PENDING);
        assertThat(message.getAggregateType()).isEqualTo("audit");
        assertThat(message.getPayload())
                .contains("pub_probe_1")
                .contains("caller-1")
                .doesNotContain("p1-secret");
    }

    @Test
    void publishJoinsCallerTransactionAndRollbackDiscards() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        AuditEvent committed = sample("pub_probe_tx_ok");
        template.executeWithoutResult(tx -> publisher.publish(committed));
        assertThat(findByAggregateId(committed.getId().toString())).isNotNull();

        AuditEvent rolledBack = sample("pub_probe_tx_rollback");
        assertThatThrownBy(() -> template.executeWithoutResult(tx -> {
            publisher.publish(rolledBack);
            throw new IllegalStateException("rollback-probe");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(findByAggregateId(rolledBack.getId().toString())).isNull();
    }

    @Test
    void publishSanitizesSensitiveFields() {
        AuditEvent event = sample("pub_probe_secret");
        event.setInput("{\"password\":\"p1-secret\",\"k\":\"v\"}");
        publisher.publish(event);

        OutboxMessage message = findByAggregateId(event.getId().toString());
        assertThat(message.getPayload())
                .contains("\\\"password\\\":\\\"***\\\"")
                .doesNotContain("p1-secret");
    }

    private OutboxMessage findByAggregateId(String aggregateId) {
        List<OutboxMessage> all = outboxRepository.findAll();
        return all.stream()
                .filter(message -> aggregateId.equals(message.getAggregateId()))
                .findFirst()
                .orElse(null);
    }

    private AuditEvent sample(String toolName) {
        AuditEvent event = new AuditEvent();
        event.setCallerId("caller-1");
        event.setTenantId("tenant-a");
        event.setToolName(toolName);
        event.setInput("{\"k\":\"v\"}");
        event.setStatus("EXECUTED");
        return event;
    }
}
