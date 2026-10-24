package com.example.mcp.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, UUID> {

    List<OutboxMessage> findTop50ByStatusOrderByCreatedAtAsc(String status);

    /** 队列深度统计（OutboxMetrics 的 gauge 数据源）。 */
    long countByStatus(String status);

    /** FAILED 重驱：人工排查后放回队列（retryCount 清零重新计数）。 */
    @Modifying
    @Transactional
    @Query("UPDATE OutboxMessage m SET m.status = :newStatus, m.retryCount = 0 "
            + "WHERE m.id = :id AND m.status = :fromStatus")
    int redrive(@Param("id") UUID id,
                @Param("fromStatus") String fromStatus,
                @Param("newStatus") String newStatus);
}
