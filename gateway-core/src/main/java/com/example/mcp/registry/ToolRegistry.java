package com.example.mcp.registry;

import com.example.mcp.security.ToolAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 工具注册表：网关对外暴露的工具目录。
 * sideEffect 分级同时服务权限矩阵（阶段二）、人工确认（阶段三）与审计（阶段四）。
 */
@Entity
@Table(name = "tool_registry")
public class ToolRegistry {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(length = 1024)
    private String description;

    /** JSON Schema（对象形态）；PG 落 JSONB，H2 落 JSON。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_schema", nullable = false)
    private String inputSchema;

    @Enumerated(EnumType.STRING)
    @Column(name = "side_effect", nullable = false, length = 20)
    private ToolAction sideEffect;

    @Column(name = "required_role", length = 100)
    private String requiredRole;

    @Column(name = "required_tenant", length = 100)
    private String requiredTenant;

    /** 每分钟允许的调用次数；null 表示暂不限流。 */
    @Column(name = "rate_limit")
    private Integer rateLimit;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getInputSchema() {
        return inputSchema;
    }

    public void setInputSchema(String inputSchema) {
        this.inputSchema = inputSchema;
    }

    public ToolAction getSideEffect() {
        return sideEffect;
    }

    public void setSideEffect(ToolAction sideEffect) {
        this.sideEffect = sideEffect;
    }

    public String getRequiredRole() {
        return requiredRole;
    }

    public void setRequiredRole(String requiredRole) {
        this.requiredRole = requiredRole;
    }

    public String getRequiredTenant() {
        return requiredTenant;
    }

    public void setRequiredTenant(String requiredTenant) {
        this.requiredTenant = requiredTenant;
    }

    public Integer getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(Integer rateLimit) {
        this.rateLimit = rateLimit;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
