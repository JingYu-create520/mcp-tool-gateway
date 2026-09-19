package com.example.mcp.confirmation;

import com.example.mcp.registry.ToolRegistryRequest;
import com.example.mcp.registry.ToolRegistryService;
import com.example.mcp.security.CallerContext;
import com.example.mcp.security.PermissionDeniedException;
import com.example.mcp.security.ToolAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SafeToolExecutor 策略执行测试：READ 直通、WRITE 创建 pending、
 * 确认后用【确认过的输入】执行、权限与存在性校验。
 */
@SpringBootTest
@ActiveProfiles("test")
class SafeToolExecutorIT {

    private static final String WRITE_TOOL = "executor_probe";

    @Autowired
    SafeToolExecutor executor;

    @Autowired
    ToolRegistryService toolRegistryService;

    @Autowired
    ConfirmationService confirmationService;

    @Autowired
    PendingExecutionRepository pendingRepository;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readToolGoesStraightThrough() {
        ensureWriteTool();
        // 真实 admin-key 同时具有 admin 与 user 角色（vredis_search requiredRole=user）
        authenticate("admin-user", "tenant-a", Set.of("admin", "user"));
        // vredis_search 是 READ 级内置工具（ExternalToolRegistrar 注册）
        Map<String, Object> outcome = executor.execute(
                "vredis_search", "{\"pattern\":\"x:*\"}", input -> "read-result");
        assertThat(outcome).containsEntry("status", "executed").containsEntry("result", "read-result");
    }

    @Test
    void writeToolCreatesPendingAndNeverExecutes() {
        ensureWriteTool();
        authenticate("admin-user", "tenant-a", Set.of("admin"));

        Map<String, Object> outcome = executor.execute(WRITE_TOOL, "{\"k\":\"v1\"}",
                input -> {
                    throw new AssertionError("WRITE 工具未经确认不应执行");
                });

        assertThat(outcome).containsEntry("status", "pending");
        UUID confirmationId = UUID.fromString((String) outcome.get("confirmationId"));
        PendingExecution pending = pendingRepository.findByConfirmationId(confirmationId).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(ConfirmationStatus.PENDING);
        assertThat(pending.getCallerId()).isEqualTo("admin-user");
        assertThat(pending.getToolName()).isEqualTo(WRITE_TOOL);
    }

    @Test
    void confirmedPendingExecutesWithConfirmedInput() {
        ensureWriteTool();
        authenticate("admin-user", "tenant-a", Set.of("admin"));

        String input = "{\"k\":\"cv\"}";
        Map<String, Object> first = executor.execute(WRITE_TOOL, input, exec -> "should-not-run");
        UUID confirmationId = UUID.fromString((String) first.get("confirmationId"));
        confirmationService.confirm(confirmationId);

        // result 列是 JSONB/JSON，桩执行器必须返回合法 JSON
        Map<String, Object> second = executor.execute(WRITE_TOOL, input,
                exec -> "{\"input\":" + exec + "}");
        assertThat(second).containsEntry("status", "executed");
        assertThat(second).containsEntry("result", "{\"input\":" + input + "}");
        assertThat(second).containsEntry("confirmationId", confirmationId.toString());
        assertThat(pendingRepository.findByConfirmationId(confirmationId).orElseThrow().getStatus())
                .isEqualTo(ConfirmationStatus.EXECUTED);
    }

    @Test
    void missingRequiredRoleDenied() {
        ensureWriteTool();
        authenticate("user-1", "tenant-a", Set.of("user"));
        assertThatThrownBy(() -> executor.execute(WRITE_TOOL, "{\"k\":\"v\"}", input -> "no"))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void missingToolThrows() {
        authenticate("admin-user", "tenant-a", Set.of("admin"));
        assertThatThrownBy(() -> executor.execute("no_such_tool", "{}", input -> "no"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void ensureWriteTool() {
        if (toolRegistryService.findByName(WRITE_TOOL).isEmpty()) {
            toolRegistryService.register(new ToolRegistryRequest(
                    WRITE_TOOL,
                    "SafeToolExecutor 探针",
                    "{\"type\":\"object\",\"properties\":{\"k\":{\"type\":\"string\"}},\"required\":[\"k\"]}",
                    ToolAction.WRITE,
                    "admin",
                    null,
                    null,
                    true));
        }
    }

    private void authenticate(String callerId, String tenantId, Set<String> roles) {
        CallerContext caller = new CallerContext(callerId, tenantId, roles);
        Authentication authentication = new UsernamePasswordAuthenticationToken(caller, null, java.util.List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
