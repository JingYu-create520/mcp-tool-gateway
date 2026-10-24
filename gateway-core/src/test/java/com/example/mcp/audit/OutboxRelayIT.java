package com.example.mcp.audit;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbox 投递验证：PENDING → audit_log 落库 + 消息删除（outbox 只作传输载体）；
 * 坏 payload 重试 3 次后 FAILED（保留现场，且不产生 audit_log 记录）；
 * 队列深度 gauge（mcp_outbox_messages）跟随投递进展。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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

    @Autowired
    MeterRegistry meterRegistry;

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    private org.springframework.web.client.RestClient adminClient() {
        return org.springframework.web.client.RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-API-Key", "admin-key")
                .build();
    }

    @Test
    void redriveEndpointReturns202ForFailedAnd400ForOthers() {
        // 直接造一条 FAILED 消息 → Controller 重驱返回 202，消息回 PENDING
        OutboxMessage failed = new OutboxMessage();
        failed.setAggregateType("audit");
        failed.setAggregateId(UUID.randomUUID().toString());
        failed.setEventType("TOOL_CALL");
        failed.setPayload("{}");
        failed.setStatus(OutboxMessage.STATUS_FAILED);
        failed.setRetryCount(OutboxRelay.MAX_RETRIES);
        failed.setCreatedAt(Instant.now());
        OutboxMessage saved = outboxRepository.save(failed);

        java.util.Map<String, Object> body = adminClient().post()
                .uri("/admin/audit/outbox/{id}/retry", saved.getId())
                .retrieve()
                .body(java.util.Map.class);
        assertThat(body).containsEntry("status", "PENDING");
        assertThat(outboxRepository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxMessage.STATUS_PENDING);

        // 已回 PENDING 的消息再重驱 → 400（仅 FAILED 可重驱）
        org.springframework.web.client.RestClientResponseException e =
                org.assertj.core.api.Assertions.catchThrowableOfType(
                        org.springframework.web.client.RestClientResponseException.class,
                        () -> adminClient().post()
                                .uri("/admin/audit/outbox/{id}/retry", saved.getId())
                                .retrieve()
                                .toEntity(String.class));
        assertThat(e.getStatusCode().value()).isEqualTo(400);
        outboxRepository.delete(saved);
    }

    @Test
    void relayDeliversPendingEventAndDiscardsMessage() {
        AuditEvent event = sample("relay_probe_ok");
        publisher.publish(event);

        awaitDiscarded(event.getId().toString());

        // 消息已删除（表不膨胀），审计已落库
        assertThat(outboxRepository.findAll())
                .noneMatch(m -> event.getId().toString().equals(m.getAggregateId()));
        List<AuditEvent> rows = auditRepository.findByToolNameAndStatusOrderByCreatedAtDesc(
                "relay_probe_ok", "EXECUTED");
        assertThat(rows).anyMatch(row -> row.getId().equals(event.getId()));
    }

    @Test
    void outboxDepthGaugeReflectsQueueState() {
        // gauge 与 count 是两次独立查询，中间后台 relay 可能删一行：
        // 断言 gauge 落在两次 count 采样的闭区间内（同源数据，漂移最多 1 行）
        long before = outboxRepository.countByStatus(OutboxMessage.STATUS_PENDING);
        double gauge = pendingDepth();
        long after = outboxRepository.countByStatus(OutboxMessage.STATUS_PENDING);
        assertThat(gauge).isBetween((double) Math.min(before, after), (double) Math.max(before, after));
        assertThat(failedDepth())
                .isEqualTo((double) outboxRepository.countByStatus(OutboxMessage.STATUS_FAILED));
    }

    @Test
    void failedMessageCanBeRedrivenAndThenDelivered() {
        // 制造一条 FAILED（坏 payload 重试耗尽）
        OutboxMessage bad = new OutboxMessage();
        bad.setAggregateType("audit");
        bad.setAggregateId(UUID.randomUUID().toString());
        bad.setEventType("TOOL_CALL");
        bad.setPayload("{\"createdAt\":\"not-a-date\"}");
        bad.setStatus(OutboxMessage.STATUS_PENDING);
        bad.setRetryCount(0);
        bad.setCreatedAt(Instant.now());
        bad = outboxRepository.save(bad);
        for (int i = 0; i < 10 && !outboxRepository.findById(bad.getId())
                .map(m -> OutboxMessage.STATUS_FAILED.equals(m.getStatus())).orElse(false); i++) {
            relay.relayOnce();
        }
        assertThat(outboxRepository.findById(bad.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxMessage.STATUS_FAILED);

        // 人工"修复"payload 后重驱 → 回到 PENDING → 正常投递并删除
        // payload 必须是完整合法的 AuditEvent JSON（id/createdAt 由发布方负责赋值）
        OutboxMessage failed = outboxRepository.findById(bad.getId()).orElseThrow();
        failed.setPayload("{\"id\":\"" + UUID.randomUUID() + "\",\"createdAt\":\"2026-01-01T00:00:00Z\","
                + "\"callerId\":\"redrive-caller\",\"tenantId\":\"tenant-a\","
                + "\"toolName\":\"redrive_probe\",\"input\":\"{}\",\"status\":\"EXECUTED\"}");
        outboxRepository.save(failed);

        int redriven = outboxRepository.redrive(bad.getId(), OutboxMessage.STATUS_FAILED,
                OutboxMessage.STATUS_PENDING);
        assertThat(redriven).isEqualTo(1);
        // 非 FAILED 状态的重驱请求无效
        assertThat(outboxRepository.redrive(bad.getId(), OutboxMessage.STATUS_FAILED,
                OutboxMessage.STATUS_PENDING)).isZero();

        awaitDiscarded(bad.getId().toString());
        assertThat(auditRepository.findByToolNameAndStatusOrderByCreatedAtDesc("redrive_probe", "EXECUTED"))
                .anyMatch(row -> "redrive-caller".equals(row.getCallerId()));
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
        // FAILED 消息必须保留原始 payload 现场（供人工排查后修复重驱），不得被部分改写
        OutboxMessage failedRow = outboxRepository.findById(bad.getId()).orElseThrow();
        assertThat(failedRow.getPayload()).isEqualTo("{\"createdAt\":\"not-a-date\"}");
        assertThat(failedRow.getRetryCount()).isEqualTo(OutboxRelay.MAX_RETRIES);
    }

    private double pendingDepth() {
        return meterRegistry.find("mcp.outbox.messages").tag("status", "PENDING").gauge().value();
    }

    private double failedDepth() {
        return meterRegistry.find("mcp.outbox.messages").tag("status", "FAILED").gauge().value();
    }

    private void awaitDiscarded(String aggregateId) {
        for (int i = 0; i < 20; i++) {
            relay.relayOnce();
            boolean stillThere = outboxRepository.findAll().stream()
                    .anyMatch(m -> aggregateId.equals(m.getAggregateId()));
            if (!stillThere) {
                return;
            }
        }
        throw new AssertionError("消息未被投递删除: " + aggregateId);
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
