package com.example.mcp.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 把 PermissionDeniedException 统一映射为 403 + {error: "permission_denied", message: "..."}。
 * MCP 工具调用内的权限拒绝不经过本 Advice（由调用处理器转为 isError 结果）。
 */
@RestControllerAdvice
public class SecurityExceptionHandler {

    @ExceptionHandler(PermissionDeniedException.class)
    public ResponseEntity<Map<String, String>> handlePermissionDenied(PermissionDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "permission_denied", "message",
                        e.getMessage() == null ? "" : e.getMessage()));
    }
}
