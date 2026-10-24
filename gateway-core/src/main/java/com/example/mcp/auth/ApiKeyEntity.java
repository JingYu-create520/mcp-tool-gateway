package com.example.mcp.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * API Key 持久化实体。keyHash = SHA-256(原始密钥) 的十六进制编码，
 * 原始密钥不在数据库中（安全要求）。由 DbApiKeySeeder 启动时写入种子密钥。
 */
@Entity
@Table(name = "api_key")
public class ApiKeyEntity {

    @Id
    private UUID id;

    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    @Column(name = "caller_id", nullable = false)
    private String callerId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    /** 逗号分隔的角色列表，如 "admin,user,confirmer"。 */
    @Column(nullable = false, length = 500)
    private String roles;

    @Column(nullable = false)
    private boolean enabled = true;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getKeyHash() { return keyHash; }
    public void setKeyHash(String keyHash) { this.keyHash = keyHash; }

    public String getCallerId() { return callerId; }
    public void setCallerId(String callerId) { this.callerId = callerId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getRoles() { return roles; }
    public void setRoles(String roles) { this.roles = roles; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
