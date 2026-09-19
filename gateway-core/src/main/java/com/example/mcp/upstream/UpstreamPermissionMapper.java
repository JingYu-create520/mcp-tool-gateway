package com.example.mcp.upstream;

import org.springframework.stereotype.Component;

/**
 * upstream 工具的权限映射：工具名含 write/delete/upsert 自动提升为 admin，
 * 其余用 upstream 配置的 defaultRequiredRole（默认 user）。
 */
@Component
public class UpstreamPermissionMapper {

    public String requiredRole(UpstreamProperties.Upstream upstream, String upstreamToolName) {
        String lower = upstreamToolName == null ? "" : upstreamToolName.toLowerCase();
        boolean privileged = lower.contains("write") || lower.contains("delete") || lower.contains("upsert");
        return privileged ? "admin" : upstream.defaultRole();
    }
}
