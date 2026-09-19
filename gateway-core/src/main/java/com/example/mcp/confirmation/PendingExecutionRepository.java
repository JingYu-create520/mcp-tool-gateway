package com.example.mcp.confirmation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PendingExecutionRepository extends JpaRepository<PendingExecution, UUID> {

    Optional<PendingExecution> findByConfirmationId(UUID confirmationId);

    List<PendingExecution> findByStatusAndExpiresAtBefore(ConfirmationStatus status, Instant cutoff);

    /** SafeToolExecutor 查找"同工具 + 同调用方 + 同输入"的已确认待执行记录。 */
    Optional<PendingExecution> findFirstByToolNameAndCallerIdAndInputHashAndStatusOrderByCreatedAtDesc(
            String toolName, String callerId, String inputHash, ConfirmationStatus status);
}
