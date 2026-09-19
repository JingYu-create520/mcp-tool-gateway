package com.example.mcp.security;

/**
 * 调用方缺失访问所需的角色/租户/身份时抛出；
 * REST 侧由 SecurityExceptionHandler 统一映射为 403 + {error: permission_denied}，
 * MCP 工具调用内由处理器转为 isError=true 的工具结果。
 */
public class PermissionDeniedException extends RuntimeException {

    public PermissionDeniedException(String message) {
        super(message);
    }
}
