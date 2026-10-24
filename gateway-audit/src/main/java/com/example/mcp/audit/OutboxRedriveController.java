package com.example.mcp.audit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Outbox FAILED 消息的重驱端点（admin 角色，/admin/** URL 层已保护）。
 * 用途闭环：OutboxMetrics 告警发现 FAILED 积压 → 人工排查根因（通常是 DB 故障恢复后）→
 * 调本端点放回队列，避免手工 SQL。仅 FAILED 可重驱，重试计数清零重新累计。
 */
@Tag(name = "审计查询", description = "工具调用轨迹的动态过滤与分页（证据链只读）；含 Outbox 失败消息重驱")
@RestController
@RequestMapping("/admin/audit/outbox")
public class OutboxRedriveController {

    private final OutboxMessageRepository outboxRepository;

    public OutboxRedriveController(OutboxMessageRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @Operation(summary = "重驱失败消息", description = "仅 FAILED 可重驱（retryCount 清零）；配合 OutboxMessagesFailed 告警使用")
    @PostMapping("/{id}/retry")
    public ResponseEntity<Map<String, String>> retry(@PathVariable UUID id) {
        int updated = outboxRepository.redrive(id, OutboxMessage.STATUS_FAILED, OutboxMessage.STATUS_PENDING);
        if (updated == 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "消息不存在或不在 FAILED 状态: " + id));
        }
        return ResponseEntity.accepted().body(Map.of("status", "PENDING", "id", id.toString()));
    }
}
