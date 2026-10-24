package com.example.mcp.confirmation;

import com.example.mcp.security.CallerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * get_confirmation_status 的调用方绑定：只能查自己的确认单，
 * 他人的确认单按不存在处理（不给探测/枚举空间）。
 */
@SpringBootTest
@ActiveProfiles("test")
class ConfirmationToolsIT {

    @Autowired
    ConfirmationTools tools;

    @Autowired
    ConfirmationService confirmationService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ownerSeesStatusButOthersGetNotFound() {
        authenticate("agent-a");
        PendingExecution pending = confirmationService.createPending("probe_tool",
                new CallerContext("agent-a", "tenant-a", Set.of("user")), "{\"k\":\"v\"}");

        // 本人可查
        assertThat(tools.getStatus(pending.getConfirmationId().toString()))
                .isEqualTo(ConfirmationStatus.PENDING.name());

        // 他人查询：按不存在处理（不是 403 语义，避免暴露该 id 的存在性）
        authenticate("agent-b");
        assertThatThrownBy(() -> tools.getStatus(pending.getConfirmationId().toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void unknownIdThrows() {
        authenticate("agent-a");
        assertThatThrownBy(() -> tools.getStatus(UUID.randomUUID().toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void authenticate(String callerId) {
        CallerContext caller = new CallerContext(callerId, "tenant-a", Set.of("user"));
        Authentication authentication = new UsernamePasswordAuthenticationToken(caller, null, java.util.List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
