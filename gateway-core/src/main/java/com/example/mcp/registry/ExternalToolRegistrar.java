package com.example.mcp.registry;

import com.example.mcp.tools.ToolDefinition;
import com.example.mcp.tools.ToolExecutorResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 外部工具引导：启动时（先于 DynamicToolRegistry）收集所有 ToolExecutorResolver 实现，
 * 把工具定义落 tool_registry（存在则按代码更新 —— 代码是内置工具的唯一事实来源），
 * 并把执行器绑定进 DynamicToolRegistry 的执行器表。
 * MCP 侧注册仍统一由 DynamicToolRegistry 完成（避免重复 addTool）。
 */
@Component
@Order(0)
public class ExternalToolRegistrar implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ExternalToolRegistrar.class);

    private final ToolRegistryRepository repository;
    private final DynamicToolRegistry dynamicToolRegistry;
    private final List<ToolExecutorResolver> resolvers;

    public ExternalToolRegistrar(ToolRegistryRepository repository,
                                 DynamicToolRegistry dynamicToolRegistry,
                                 List<ToolExecutorResolver> resolvers) {
        this.repository = repository;
        this.dynamicToolRegistry = dynamicToolRegistry;
        this.resolvers = resolvers;
    }

    @Override
    public void run(ApplicationArguments args) {
        int count = 0;
        for (ToolExecutorResolver resolver : resolvers) {
            for (ToolDefinition definition : resolver.definitions()) {
                upsertDefinition(definition);
                dynamicToolRegistry.registerExecutor(definition.name(), resolver.executorFor(definition.name()));
                count++;
            }
        }
        log.info("外部工具引导完成，共处理 {} 个工具定义", count);
    }

    private void upsertDefinition(ToolDefinition definition) {
        ToolRegistry tool = repository.findByName(definition.name()).orElseGet(ToolRegistry::new);
        boolean isNew = tool.getId() == null;
        tool.setName(definition.name());
        tool.setDescription(definition.description());
        tool.setInputSchema(definition.inputSchema());
        tool.setSideEffect(definition.sideEffect());
        tool.setRequiredRole(definition.requiredRole());
        tool.setRequiredTenant(definition.requiredTenant());
        Instant now = Instant.now();
        if (isNew) {
            tool.setId(UUID.randomUUID());
            tool.setEnabled(true);
            tool.setCreatedAt(now);
        }
        tool.setUpdatedAt(now);
        repository.save(tool);
    }
}
