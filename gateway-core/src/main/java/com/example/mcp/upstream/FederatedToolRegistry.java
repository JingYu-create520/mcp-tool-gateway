package com.example.mcp.upstream;

import com.example.mcp.registry.DynamicToolRegistry;
import com.example.mcp.registry.ToolRegistry;
import com.example.mcp.registry.ToolRegistryRepository;
import com.example.mcp.security.ToolAction;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具联邦：启动时与每轮健康检查后，把 enabled 且可连通的 upstream 的工具
 * 以 {upstreamName}__{toolName} 前缀并入工具清单（MCP Server + ToolRegistry 数据库），
 * 执行器统一绑定到 UpstreamToolRouter（熔断 + 超时隔离 + 权限映射）。
 * upstream 不可用时其工具自动从 tools/list 剔除，恢复后自动加回。
 */
@Component
@Order(20)
public class FederatedToolRegistry implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FederatedToolRegistry.class);
    private static final String SEPARATOR = "__";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final UpstreamProperties properties;
    private final McpClientPool pool;
    private final DynamicToolRegistry dynamicToolRegistry;
    private final ToolRegistryRepository repository;
    private final UpstreamPermissionMapper permissionMapper;
    private final UpstreamToolRouter router;

    /** 当前已注册的联邦工具（upstream → 完整工具名列表），用于不健康时剔除与复活时去重。 */
    private final Map<String, List<String>> trackedByUpstream = new ConcurrentHashMap<>();

    public FederatedToolRegistry(UpstreamProperties properties,
                                 McpClientPool pool,
                                 DynamicToolRegistry dynamicToolRegistry,
                                 ToolRegistryRepository repository,
                                 UpstreamPermissionMapper permissionMapper,
                                 UpstreamToolRouter router) {
        this.properties = properties;
        this.pool = pool;
        this.dynamicToolRegistry = dynamicToolRegistry;
        this.repository = repository;
        this.permissionMapper = permissionMapper;
        this.router = router;
    }

    @Override
    public void run(ApplicationArguments args) {
        syncUpstreamTools();
    }

    /** 同步所有 enabled upstream 的工具清单（启动时与每轮健康检查后调用）。 */
    public synchronized void syncUpstreamTools() {
        for (UpstreamProperties.Upstream upstream : properties.upstreams()) {
            if (!upstream.isEnabled()) {
                continue;
            }
            McpUpstreamClient client = pool.byName(upstream.name());
            if (client == null) {
                continue;
            }
            List<McpSchema.Tool> remoteTools;
            try {
                remoteTools = client.listTools();
            } catch (Exception e) {
                log.warn("upstream '{}' 不可达，其联邦工具已从清单剔除: {}", upstream.name(), e.getMessage());
                removeTracked(upstream.name());
                continue;
            }
            registerTracked(upstream, remoteTools);
        }
    }

    private void registerTracked(UpstreamProperties.Upstream upstream, List<McpSchema.Tool> remoteTools) {
        List<String> names = new ArrayList<>();
        for (McpSchema.Tool remote : remoteTools) {
            String fullName = upstream.name() + SEPARATOR + remote.name();
            ToolRegistry row = upsertRegistryRow(upstream, fullName, remote);
            dynamicToolRegistry.registerExternalTool(row, router.executorFor(upstream.name(), remote.name()));
            names.add(fullName);
        }
        // 远端工具清单里已消失的，从 MCP 清单剔除
        for (String stale : trackedByUpstream.getOrDefault(upstream.name(), List.of())) {
            if (!names.contains(stale)) {
                dynamicToolRegistry.remove(stale);
                log.info("upstream '{}' 的工具 '{}' 已在远端消失，从联邦清单剔除", upstream.name(), stale);
            }
        }
        trackedByUpstream.put(upstream.name(), names);
        if (!names.isEmpty()) {
            log.info("upstream '{}' 联邦工具同步完成: {}", upstream.name(), names);
        }
    }

    private void removeTracked(String upstreamName) {
        for (String fullName : trackedByUpstream.getOrDefault(upstreamName, List.of())) {
            dynamicToolRegistry.remove(fullName);
        }
        trackedByUpstream.remove(upstreamName);
    }

    private ToolRegistry upsertRegistryRow(UpstreamProperties.Upstream upstream, String fullName, McpSchema.Tool remote) {
        ToolRegistry row = repository.findByName(fullName).orElseGet(ToolRegistry::new);
        boolean isNew = row.getId() == null;
        row.setName(fullName);
        row.setDescription("[" + upstream.name() + "] "
                + (remote.description() == null ? "" : remote.description()));
        row.setInputSchema(JSON.writeValueAsString(remote.inputSchema()));
        row.setSideEffect(ToolAction.READ);
        row.setRequiredRole(permissionMapper.requiredRole(upstream, remote.name()));
        row.setRequiredTenant(null);
        row.setRateLimit(null);
        Instant now = Instant.now();
        if (isNew) {
            row.setId(UUID.randomUUID());
            row.setEnabled(true);
            row.setCreatedAt(now);
        }
        row.setUpdatedAt(now);
        return repository.save(row);
    }
}
