package com.example.mcp.confirmation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 人工确认入口（REST + 确认台列表）。/confirmations/** 仅 confirmer 角色（SecurityConfig），
 * 与 AI 客户端凭据隔离；静态确认台页面在 /confirmation-console.html（同样走 X-API-Key 认证）。
 */
@Tag(name = "确认台", description = "WRITE/DANGEROUS 工具的人工确认（仅 confirmer 角色）")
@RestController
@RequestMapping("/confirmations")
public class ConfirmationController {

    private final ConfirmationService confirmationService;

    public ConfirmationController(ConfirmationService confirmationService) {
        this.confirmationService = confirmationService;
    }

    @Operation(summary = "确认单据", description = "仅 PENDING 时生效（幂等）；过期确认单会被置为 EXPIRED")
    @PostMapping("/{id}/confirm")
    public PendingExecution confirm(@PathVariable UUID id) {
        return confirmationService.confirm(id);
    }

    @Operation(summary = "拒绝单据", description = "仅 PENDING 时生效（幂等）；拒绝是终态，同输入需重新确认")
    @PostMapping("/{id}/reject")
    public PendingExecution reject(@PathVariable UUID id) {
        return confirmationService.reject(id);
    }

    @Operation(summary = "查询单据", description = "按确认单 ID 查询当前状态与输入快照")
    @GetMapping("/{id}")
    public PendingExecution get(@PathVariable UUID id) {
        return confirmationService.find(id);
    }

    /**
     * 确认台列表：按状态过滤（逗号分隔多状态，默认 PENDING），最近 50 条倒序。
     * 非法状态名返回 400 —— 不静默忽略，避免"以为在看过期单其实在看待确认"的误导。
     */
    @Operation(summary = "确认单列表", description = "按状态过滤（逗号分隔多状态），最近 50 条倒序")
    @GetMapping
    public List<PendingExecution> list(
            @RequestParam(defaultValue = "PENDING") String status) {
        Set<ConfirmationStatus> statuses = Arrays.stream(status.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(ConfirmationStatus::valueOf)
                .collect(Collectors.toUnmodifiableSet());
        if (statuses.isEmpty()) {
            throw new IllegalArgumentException("status 不能为空");
        }
        return confirmationService.list(statuses);
    }

    @ExceptionHandler(ConfirmationService.NotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(ConfirmationService.NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "not_found", "message",
                        e.getMessage() == null ? "" : e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        // 覆盖列表端点的非法状态名等参数错误；NotFoundException 是其子类但被上面的更具体 handler 优先捕获
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "bad_request", "message",
                        e.getMessage() == null ? "" : e.getMessage()));
    }
}
