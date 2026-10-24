package com.example.mcp.upstream;

import com.example.mcp.security.ToolAction;
import org.springframework.stereotype.Component;

/**
 * upstream 工具的安全分级映射（网关无法感知远端真实语义，只能按工具名启发式判断）：
 * 名字含 delete/remove/purge/drop → DANGEROUS；含 write/upsert/create/update → WRITE；
 * 其余 READ。WRITE/DANGEROUS 会进入人工确认状态机，requiredRole 提升为 admin。
 * 保守方向：宁可把只读工具误判为 WRITE（多一次确认）。
 * 已知局限：名字完全不含副作用关键词的写工具（如 put_data）会被漏判为 READ 直通——
 * 启发式只能覆盖命名惯例内的名字，真正的防线是 upstream 可信边界与 requiredRole。
 */
@Component
public class UpstreamPermissionMapper {

    public ToolAction sideEffect(String upstreamToolName) {
        String lower = lower(upstreamToolName);
        if (containsAny(lower, "delete", "remove", "purge", "drop")) {
            return ToolAction.DANGEROUS;
        }
        if (containsAny(lower, "write", "upsert", "create", "update", "deploy", "flush")) {
            return ToolAction.WRITE;
        }
        return ToolAction.READ;
    }

    public String requiredRole(UpstreamProperties.Upstream upstream, String upstreamToolName) {
        return sideEffect(upstreamToolName) == ToolAction.READ
                ? upstream.defaultRole()
                : "admin";
    }

    private static String lower(String name) {
        return name == null ? "" : name.toLowerCase();
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
