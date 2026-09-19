package com.example.mcp.tools;

import com.example.mcp.security.ToolAction;

/**
 * 外部工具定义（SPI）：由各后端集成模块声明，gateway-core 的
 * ExternalToolRegistrar 在启动时据此维护 tool_registry。
 */
public record ToolDefinition(
        String name,
        String description,
        String inputSchema,
        ToolAction sideEffect,
        String requiredRole,
        String requiredTenant) {
}
