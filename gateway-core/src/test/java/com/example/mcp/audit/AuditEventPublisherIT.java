package com.example.mcp.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

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
    AuditEventRepository auditRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void publishWritesPendingOutboxRow() {
        AuditEvent event = sample("pub_probe_1");
        publisher.publish(event);

        // 共享 H2 库里其他上下文的 5 秒 relay 可能恰好在断言前投递并删除该行：
        // "outbox 仍有 PENDING 行"与"已落 audit_log"都是 publish 成功的合法终态
        OutboxMessage message = findByAggregateId(event.getId().toString());
        boolean stillPending = message != null;
        if (stillPending) {
            assertThat(message.getStatus()).isEqualTo(OutboxMessage.STATUS_PENDING);
            assertThat(message.getAggregateType()).isEqualTo("audit");
            assertThat(message.getPayload())
                    .contains("pub_probe_1")
                    .contains("caller-1")
                    .doesNotContain("p1-secret");
        } else {
            assertThat(auditRepository.findByToolNameAndStatusOrderByCreatedAtDesc("pub_probe_1", "EXECUTED"))
                    .anyMatch(row -> row.getId().equals(event.getId()));
        }
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

    @Test
    void oversizedFieldsAreTruncatedWithMarker() {
        AuditEvent event = sample("pub_probe_huge");
        // 超过 MAX_FIELD_LENGTH 的入参：截断留痕，audit_log 不被单条记录拖爆
        event.setInput("{\"blob\":\"" + "x".repeat(AuditEventPublisher.MAX_FIELD_LENGTH + 100) + "\"}");
        publisher.publish(event);

        assertThat(findPayloadOrAuditFallback(event.getId(), "pub_probe_huge")).contains("truncated");
    }

    /** outbox 行可能已被后台 relay 投递删除：此时从 audit_log 取已落库的 input。 */
    private String findPayloadOrAuditFallback(UUID eventId, String toolName) {
        OutboxMessage message = findByAggregateId(eventId.toString());
        if (message != null) {
            return message.getPayload();
        }
        return auditRepository.findById(eventId).map(AuditEvent::getInput).orElse("");
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
