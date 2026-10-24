package com.example.mcp.confirmation;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 过期扫描：默认每 5 分钟（gateway.confirmation-expire-scan-ms 可覆盖）。
 * 注意：过期生效时点最迟滞后一个扫描间隔——确认有效期（gateway.confirmation-ttl-minutes）
 * 配置得比扫描间隔短时，实际作废时点 = TTL 对齐到下一次扫描；需要紧凑作废语义就把
 * 扫描间隔调到与 TTL 同量级。把超时的 PENDING（未确认）和 EXECUTING（执行中断，如进程崩溃）置为 EXPIRED。
 * ShedLock（JdbcTemplateLockProvider + shedlock 表）保证多实例下不重复扫描。
 */
@Component
public class ExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpirationScheduler.class);

    private final PendingExecutionRepository repository;
    private final ConfirmationService confirmationService;

    public ExpirationScheduler(PendingExecutionRepository repository, ConfirmationService confirmationService) {
        this.repository = repository;
        this.confirmationService = confirmationService;
    }

    @Scheduled(fixedDelayString = "${gateway.confirmation-expire-scan-ms:300000}")
    @SchedulerLock(name = "expirePending", lockAtMostFor = "4m", lockAtLeastFor = "30s")
    public void expirePending() {
        // CONFIRMED：确认后未在有效期内执行，确认机会作废；
        // EXECUTING：执行中断（如进程崩溃）后无人回退；PENDING：超时未确认
        List<PendingExecution> expired = repository.findByStatusInAndExpiresAtBefore(
                List.of(ConfirmationStatus.PENDING, ConfirmationStatus.CONFIRMED,
                        ConfirmationStatus.EXECUTING), Instant.now());
        expired.forEach(pending -> confirmationService.markExpired(pending.getConfirmationId()));
        if (!expired.isEmpty()) {
            log.info("过期确认清理完成: {} 条 → EXPIRED", expired.size());
        }
    }
}
