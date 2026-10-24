package com.example.mcp.audit;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Outbox 投递器：每 5 秒扫描 PENDING 消息，写入 audit_log 后删除消息
 * （audit_log 是唯一证据链，outbox 只是传输载体，投递完不留行、表不膨胀）；
 * 失败重试 3 次后标记 FAILED（保留现场供人工排查）。ShedLock 防多实例并发扫描。
 * audit_log 主键 = 事件 id：崩溃在"落库后、删除前"时重投会主键冲突，视为已投递（幂等）。
 */
@Component
public class OutboxRelay {

    static final int MAX_RETRIES = 3;

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OutboxMessageRepository outboxRepository;
    private final AuditEventRepository auditRepository;

    public OutboxRelay(OutboxMessageRepository outboxRepository, AuditEventRepository auditRepository) {
        this.outboxRepository = outboxRepository;
        this.auditRepository = auditRepository;
    }

    @Scheduled(fixedDelay = 5000)
    @SchedulerLock(name = "outboxRelay", lockAtMostFor = "1m", lockAtLeastFor = "5s")
    public void relayScheduled() {
        relayOnce();
    }

    /** 单轮投递，测试可直接调用。 */
    public void relayOnce() {
        List<OutboxMessage> pending =
                outboxRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxMessage.STATUS_PENDING);
        for (OutboxMessage message : pending) {
            relay(message);
        }
    }

    private void relay(OutboxMessage message) {
        try {
            AuditEvent event = JSON.readValue(message.getPayload(), AuditEvent.class);
            auditRepository.save(event);
            outboxRepository.delete(message);
        } catch (DataIntegrityViolationException duplicate) {
            // 同一事件 id 已落库：并发投递或崩溃后重投的重复消息，按成功处理（幂等）
            outboxRepository.delete(message);
        } catch (Exception e) {
            int retries = message.getRetryCount() + 1;
            message.setRetryCount(retries);
            if (retries >= MAX_RETRIES) {
                message.setStatus(OutboxMessage.STATUS_FAILED);
                log.error("Outbox 投递失败并放弃: id={}, type={}, retry={}",
                        message.getId(), message.getEventType(), retries, e);
            } else {
                log.warn("Outbox 投递失败，将重试: id={}, retry={}/{}",
                        message.getId(), retries, MAX_RETRIES, e);
            }
            outboxRepository.save(message);
        }
    }
}
