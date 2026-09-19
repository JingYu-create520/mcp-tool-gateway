package com.example.mcp.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbox 投递验证：PENDING → audit_log 落库 + SENT；
 * 坏 payload 重试 3 次后 FAILED（且不产生 audit_log 记录）。
 */
@SpringBootTest
@ActiveProfiles("test")
class OutboxRelayIT {

    @Autowired
    OutboxRelay relay;

    @Autowired
    OutboxMessageRepository outboxRepository;

    @Autowired
    AuditEventRepository auditRepository;

    @Autowired
    AuditEventPublisher publisher;

    @Test
    void relayDeliversPendingEventAndMarksSent() {
        AuditEvent event = sample("relay_probe_ok");
        publisher.publish(event);

        OutboxMessage message = awaitMessageStatus(event.getId().toString(), OutboxMessage.STATUS_SENT);

        assertThat(message.getSentAt()).isNotNull();
        List<AuditEvent> rows = auditRepository.findByToolNameAndStatusOrderByCreatedAtDesc(
                "relay_probe_ok", "EXECUTED");
        assertThat(rows).anyMatch(row -> row.getId().equals(event.getId()));
    }

    @Test
    void relayRetriesThenMarksFailed() {
        OutboxMessage bad = new OutboxMessage();
        bad.setAggregateType("audit");
        bad.setAggregateId(UUID.randomUUID().toString());
        bad.setEventType("TOOL_CALL");
        // payload 必须是合法 JSON（JSONB/JSON 列约束），但反序列化失败以验证重试路径
        bad.setPayload("{\"createdAt\":\"not-a-date\"}");
        bad.setStatus(OutboxMessage.STATUS_PENDING);
        bad.setRetryCount(0);
        bad.setCreatedAt(Instant.now());
        bad = outboxRepository.save(bad);

        OutboxMessage current = bad;
        for (int i = 0; i < 10 && !OutboxMessage.STATUS_FAILED.equals(current.getStatus()); i++) {
            relay.relayOnce();
            current = outboxRepository.findById(bad.getId()).orElseThrow();
        }

        assertThat(current.getStatus()).isEqualTo(OutboxMessage.STATUS_FAILED);
        assertThat(current.getRetryCount()).isGreaterThanOrEqualTo(OutboxRelay.MAX_RETRIES);
        // FAILED 的消息不应产生审计记录
        assertThat(auditRepository.findByToolNameAndStatusOrderByCreatedAtDesc("relay_probe_bad", "EXECUTED"))
                .isEmpty();
    }

    private OutboxMessage awaitMessageStatus(String aggregateId, String expectedStatus) {
        OutboxMessage message = null;
        for (int i = 0; i < 20; i++) {
            relay.relayOnce();
            message = outboxRepository.findAll().stream()
                    .filter(m -> aggregateId.equals(m.getAggregateId()))
                    .findFirst()
                    .orElse(null);
            if (message != null && expectedStatus.equals(message.getStatus())) {
                return message;
            }
        }
        return message;
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
