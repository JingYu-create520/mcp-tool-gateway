package com.example.mcp.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, UUID> {

    List<OutboxMessage> findTop50ByStatusOrderByCreatedAtAsc(String status);
}
