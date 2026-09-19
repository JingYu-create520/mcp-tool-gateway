package com.example.mcp.tools.vredis;

import com.example.mcp.security.ToolAction;
import com.example.mcp.tools.ToolDefinition;
import com.example.mcp.tools.ToolExecutorResolver;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

/**
 * vredis 工具集：定义 + 执行器绑定。权限矩阵（docs/permission-matrix.md）：
 * search/stats = READ + user；upsert = WRITE + admin；delete = DANGEROUS + admin。
 * 注册链路：ExternalToolRegistrar（core）→ tool_registry + DynamicToolRegistry 执行器表
 * → 全部调用经 SafeToolExecutor（WRITE/DANGEROUS 必须人工确认），不走注解直通。
 */
@Component
public class VredisToolResolver implements ToolExecutorResolver {

    private static final String SEARCH_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\",\"description\":\"键名匹配模式，例如 user:*\"}},\"required\":[\"pattern\"]}";
    private static final String STATS_SCHEMA =
            "{\"type\":\"object\",\"properties\":{}}";
    private static final String UPSERT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\",\"description\":\"键名\"},\"value\":{\"type\":\"string\",\"description\":\"值\"},\"ttlSeconds\":{\"type\":\"integer\",\"description\":\"过期秒数，可选\"}},\"required\":[\"key\",\"value\"]}";
    private static final String DELETE_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\",\"description\":\"要删除的键名\"}},\"required\":[\"key\"]}";

    private final VredisHttpClient client;

    public VredisToolResolver(VredisHttpClient client) {
        this.client = client;
    }

    @Override
    public List<ToolDefinition> definitions() {
        return List.of(
                new ToolDefinition("vredis_search", "按模式检索 vredis 中的键",
                        SEARCH_SCHEMA, ToolAction.READ, "user", null),
                new ToolDefinition("vredis_stats", "查看 vredis 的统计信息（键数量等）",
                        STATS_SCHEMA, ToolAction.READ, "user", null),
                new ToolDefinition("vredis_upsert", "写入/更新一个键值对（需人工确认）",
                        UPSERT_SCHEMA, ToolAction.WRITE, "admin", null),
                new ToolDefinition("vredis_delete", "删除一个键（危险操作，需人工确认）",
                        DELETE_SCHEMA, ToolAction.DANGEROUS, "admin", null));
    }

    @Override
    public Function<String, String> executorFor(String toolName) {
        return switch (toolName) {
            case "vredis_search" -> client::search;
            case "vredis_stats" -> input -> client.stats();
            case "vredis_upsert" -> client::upsert;
            case "vredis_delete" -> client::delete;
            default -> null;
        };
    }
}
