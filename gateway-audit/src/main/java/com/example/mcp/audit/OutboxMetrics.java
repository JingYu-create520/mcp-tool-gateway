package com.example.mcp.audit;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Outbox 队列深度指标：mcp_outbox_messages{status=PENDING|FAILED}。
 *
 * <p>PENDING 常态应接近 0（5 秒一轮投递）；FAILED 大于 0 说明有审计事件重试耗尽、
 * 尚未落入 audit_log —— 这是"审计不丢"承诺唯一的暴露面，生产环境应为其配置告警。
 */
@Component
public class OutboxMetrics implements ApplicationRunner {

    private final OutboxMessageRepository outboxRepository;
    private final MeterRegistry meterRegistry;

    public OutboxMetrics(OutboxMessageRepository outboxRepository, MeterRegistry meterRegistry) {
        this.outboxRepository = outboxRepository;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void run(ApplicationArguments args) {
        Gauge.builder("mcp.outbox.messages", outboxRepository,
                        registry -> registry.countByStatus(OutboxMessage.STATUS_PENDING))
                .tag("status", "PENDING")
                .description("Outbox 待投递消息数（常态接近 0）")
                .register(meterRegistry);
        Gauge.builder("mcp.outbox.messages", outboxRepository,
                        registry -> registry.countByStatus(OutboxMessage.STATUS_FAILED))
                .tag("status", "FAILED")
                .description("Outbox 重试耗尽的失败消息数（大于 0 需人工介入）")
                .register(meterRegistry);
    }
}
