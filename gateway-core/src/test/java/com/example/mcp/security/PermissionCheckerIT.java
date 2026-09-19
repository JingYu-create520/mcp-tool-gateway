package com.example.mcp.security;

import com.example.mcp.registry.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PermissionChecker 工具级权限检查（手动构造 SecurityContext，不走 HTTP）。
 * 注：本类物理上在 gateway-core 模块 —— PermissionChecker 需要 ToolRegistry，
 * 放入 gateway-security 会造成 Maven reactor 循环依赖（详见 PermissionChecker 注释）。
 */
@SpringBootTest
@ActiveProfiles("test")
class PermissionCheckerIT {

    @Autowired
    PermissionChecker permissionChecker;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void adminRoleAndMatchingTenantPasses() {
        authenticate("tester", "tenant-a", Set.of("admin"));
        assertThatCode(() -> permissionChecker.check(tool("admin", "tenant-a")))
                .doesNotThrowAnyException();
    }

    @Test
    void userRoleOnAdminToolDenied() {
        authenticate("user-1", "tenant-a", Set.of("user"));
        assertThatThrownBy(() -> permissionChecker.check(tool("admin", null)))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("admin");
    }

    @Test
    void tenantMismatchDenied() {
        authenticate("tester", "tenant-b", Set.of("admin"));
        assertThatThrownBy(() -> permissionChecker.check(tool(null, "tenant-a")))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("tenant-a");
    }

    @Test
    void missingAuthenticationDenied() {
        assertThatThrownBy(() -> permissionChecker.check(tool(null, null)))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("X-API-Key");
    }

    private void authenticate(String callerId, String tenantId, Set<String> roles) {
        CallerContext caller = new CallerContext(callerId, tenantId, roles);
        Authentication authentication = new UsernamePasswordAuthenticationToken(caller, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private ToolRegistry tool(String requiredRole, String requiredTenant) {
        ToolRegistry tool = new ToolRegistry();
        tool.setName("probe");
        tool.setRequiredRole(requiredRole);
        tool.setRequiredTenant(requiredTenant);
        return tool;
    }
}
