package com.example.mcp.registry;

import com.example.mcp.security.ToolAction;

/**
 * /admin/tools 的注册与更新请求体（PUT 为全量更新，name 以路径为准、不可变）。
 */
public record ToolRegistryRequest(
        String name,
        String description,
        String inputSchema,
        ToolAction sideEffect,
        String requiredRole,
        String requiredTenant,
        Integer rateLimit,
        Boolean enabled) {
}
