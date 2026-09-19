package com.example.mcp.confirmation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 人工确认入口（REST）。/confirmations/** 需认证（SecurityConfig）；
 * 确认动作由人工/确认台发起，与 AI 客户端凭据的权限隔离在阶段四进一步收紧（威胁模型 T4）。
 */
@RestController
@RequestMapping("/confirmations")
public class ConfirmationController {

    private final ConfirmationService confirmationService;

    public ConfirmationController(ConfirmationService confirmationService) {
        this.confirmationService = confirmationService;
    }

    @PostMapping("/{id}/confirm")
    public PendingExecution confirm(@PathVariable UUID id) {
        return confirmationService.confirm(id);
    }

    @PostMapping("/{id}/reject")
    public PendingExecution reject(@PathVariable UUID id) {
        return confirmationService.reject(id);
    }

    @GetMapping("/{id}")
    public PendingExecution get(@PathVariable UUID id) {
        return confirmationService.find(id);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "not_found", "message",
                        e.getMessage() == null ? "" : e.getMessage()));
    }
}
