package com.example.mcp.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface AuditEventRepository
        extends JpaRepository<AuditEvent, UUID>, JpaSpecificationExecutor<AuditEvent> {

    List<AuditEvent> findByToolNameAndStatusOrderByCreatedAtDesc(String toolName, String status);
}
