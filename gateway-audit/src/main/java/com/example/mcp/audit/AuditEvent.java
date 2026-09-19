package com.example.mcp.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * 审计事件（落 audit_log 表）：一次工具调用的完整轨迹。
 * 由 OutboxRelay 从 outbox_message 异步投递而来，payload 中的字段脱敏已在发布时完成。
 * 主键为赋值式（发布时生成，无需 @GeneratedValue）并实现 Persistable（isNew 恒 true）：
 * relay 始终按新实体 INSERT，重复投递由主键冲突去重 —— at-least-once + 幂等消费。
 */
@Entity
@Table(name = "audit_log")
public class AuditEvent implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "caller_id", nullable = false)
    private String callerId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "tool_name", nullable = false)
    private String toolName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private String input;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private String output;

    /** EXECUTED / PENDING / CONFIRMED / REJECTED / DENIED / EXPIRED */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "confirmation_id", length = 64)
    private String confirmationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getCallerId() {
        return callerId;
    }

    public void setCallerId(String callerId) {
        this.callerId = callerId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getInput() {
        return input;
    }

    public void setInput(String input) {
        this.input = input;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public String getConfirmationId() {
        return confirmationId;
    }

    public void setConfirmationId(String confirmationId) {
        this.confirmationId = confirmationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    /** 始终按新实体 INSERT（主键 = 事件 id），幂等去重交给主键冲突。 */
    @Override
    public boolean isNew() {
        return true;
    }
}
