package com.example.mcp.confirmation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 人工确认记录（pending + 轮询方案；不使用 MRTR / InputRequiredResult / elicitation）。
 * 状态机：PENDING → CONFIRMED / REJECTED / EXPIRED；CONFIRMED → EXECUTED。
 * version 字段为乐观锁，防止确认与过期扫描并发改坏状态。
 */
@Entity
@Table(name = "pending_execution")
public class PendingExecution {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "confirmation_id", nullable = false, unique = true)
    private UUID confirmationId;

    @Column(name = "tool_name", nullable = false)
    private String toolName;

    @Column(name = "caller_id", nullable = false)
    private String callerId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    /** 请求输入快照：人工确认的就是这份输入，真正执行时必须用它。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String input;

    /** input 的 SHA-256；"已确认待执行"匹配用（jsonb 无法可靠做字符串等值查询）。 */
    @Column(name = "input_hash", nullable = false, length = 64)
    private String inputHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConfirmationStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private String result;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Version
    private Long version;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getConfirmationId() {
        return confirmationId;
    }

    public void setConfirmationId(UUID confirmationId) {
        this.confirmationId = confirmationId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
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

    public String getInput() {
        return input;
    }

    public void setInput(String input) {
        this.input = input;
    }

    public String getInputHash() {
        return inputHash;
    }

    public void setInputHash(String inputHash) {
        this.inputHash = inputHash;
    }

    public ConfirmationStatus getStatus() {
        return status;
    }

    public void setStatus(ConfirmationStatus status) {
        this.status = status;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public void setExecutedAt(Instant executedAt) {
        this.executedAt = executedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
