package com.example.mcp.registry;

import com.example.mcp.security.ToolAction;

import java.time.Instant;
import java.util.UUID;

public record ToolRegistryResponse(
        UUID id,
        String name,
        String description,
        String inputSchema,
        ToolAction sideEffect,
        String requiredRole,
        String requiredTenant,
        Integer rateLimit,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {

    public static ToolRegistryResponse from(ToolRegistry tool) {
        return new ToolRegistryResponse(
                tool.getId(),
                tool.getName(),
                tool.getDescription(),
                tool.getInputSchema(),
                tool.getSideEffect(),
                tool.getRequiredRole(),
                tool.getRequiredTenant(),
                tool.getRateLimit(),
                tool.isEnabled(),
                tool.getCreatedAt(),
                tool.getUpdatedAt());
    }
}
