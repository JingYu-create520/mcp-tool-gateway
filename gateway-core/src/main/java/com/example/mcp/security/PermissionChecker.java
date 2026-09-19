package com.example.mcp.security;

// 物理上位于 gateway-core 模块：check() 需要 ToolRegistry（registry 在本模块），
// 若放入 gateway-security 会形成 gateway-core ↔ gateway-security 的 Maven 循环依赖。
// 包名保持 com.example.mcp.security，与安全类型聚合（跨模块 split package）。

import com.example.mcp.registry.ToolRegistry;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 工具级权限检查：requiredRole / requiredTenant（null 表示不限制）。
 * URL 级鉴权由 SecurityConfig 负责，两者共同构成权限矩阵（docs/permission-matrix.md）。
 */
@Component
public class PermissionChecker {

    /** 取当前调用方；未认证或认证主体不是 API Key 调用方时抛 PermissionDeniedException。 */
    public CallerContext currentCaller() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CallerContext caller) {
            return caller;
        }
        throw new PermissionDeniedException("缺少有效的调用方身份（X-API-Key）");
    }

    /** 校验工具声明的 requiredRole / requiredTenant，失败抛 PermissionDeniedException。 */
    public CallerContext check(ToolRegistry tool) {
        CallerContext caller = currentCaller();
        if (tool.getRequiredRole() != null && !caller.hasRole(tool.getRequiredRole())) {
            throw new PermissionDeniedException("工具 '%s' 需要角色 %s，当前调用方 %s 拥有角色 %s"
                    .formatted(tool.getName(), tool.getRequiredRole(), caller.callerId(), caller.roles()));
        }
        if (tool.getRequiredTenant() != null && !caller.belongsToTenant(tool.getRequiredTenant())) {
            throw new PermissionDeniedException("工具 '%s' 仅限租户 %s，当前调用方 %s 属于租户 %s"
                    .formatted(tool.getName(), tool.getRequiredTenant(), caller.callerId(), caller.tenantId()));
        }
        return caller;
    }
}
