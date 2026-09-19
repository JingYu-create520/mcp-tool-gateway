package com.example.mcp.confirmation;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 过期扫描：每 5 分钟把超时未确认的 PENDING 置为 EXPIRED。
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

    @Scheduled(fixedDelay = 300_000)
    @SchedulerLock(name = "expirePending", lockAtMostFor = "4m", lockAtLeastFor = "30s")
    public void expirePending() {
        List<PendingExecution> expired =
                repository.findByStatusAndExpiresAtBefore(ConfirmationStatus.PENDING, Instant.now());
        expired.forEach(pending -> confirmationService.markExpired(pending.getConfirmationId()));
        if (!expired.isEmpty()) {
            log.info("过期确认清理完成: {} 条 → EXPIRED", expired.size());
        }
    }
}
