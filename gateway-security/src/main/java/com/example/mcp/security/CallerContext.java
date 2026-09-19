package com.example.mcp.security;

import java.util.Set;

/**
 * 已认证调用方的上下文（由 API Key 解出），贯穿 URL 鉴权、工具级权限检查与审计（阶段四）。
 */
public record CallerContext(String callerId, String tenantId, Set<String> roles) {

    public CallerContext {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public boolean hasRole(String role) {
        return role != null && roles.contains(role);
    }

    /** 目标要求的租户为 null 表示不限制。 */
    public boolean belongsToTenant(String tenantId) {
        return tenantId == null || tenantId.equals(this.tenantId);
    }
}
