package com.example.mcp.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * 审计发布器：把 AuditEvent 序列化后写入 outbox_message。
 * REQUIRED 传播——调用方处于事务内（如 ConfirmationService 的状态转换）时同事务提交，
 * 保证"业务变更 + 审计"原子落库（Outbox 模式的核心约束）；无事务时独立提交。
 * 脱敏在写入前统一完成（payload 即为脱敏后的内容）。
 */
@Service
public class AuditEventPublisher {

    public static final String AGGREGATE_TYPE = "audit";
    public static final String EVENT_TYPE_TOOL_CALL = "TOOL_CALL";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OutboxMessageRepository outboxRepository;

    public AuditEventPublisher(OutboxMessageRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public void publish(AuditEvent event) {
        if (event.getId() == null) {
            event.setId(UUID.randomUUID());
        }
        if (event.getCreatedAt() == null) {
            event.setCreatedAt(Instant.now());
        }
        event.setInput(asJson(Sanitizer.sanitize(event.getInput())));
        event.setOutput(asJson(Sanitizer.sanitize(event.getOutput())));

        OutboxMessage message = new OutboxMessage();
        message.setAggregateType(AGGREGATE_TYPE);
        message.setAggregateId(event.getId().toString());
        message.setEventType(EVENT_TYPE_TOOL_CALL);
        message.setPayload(JSON.writeValueAsString(event));
        message.setStatus(OutboxMessage.STATUS_PENDING);
        message.setRetryCount(0);
        message.setCreatedAt(Instant.now());
        outboxRepository.save(message);
    }

    /**
     * audit_log 的 input/output 列是 JSONB/JSON，只接受合法 JSON：
     * 工具输出可能是纯文本（如 echo 的原文），包装成 JSON 字符串字面量落库。
     */
    private static String asJson(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        try {
            JSON.readTree(value);
            return value;
        } catch (Exception e) {
            return JSON.writeValueAsString(value);
        }
    }
}
