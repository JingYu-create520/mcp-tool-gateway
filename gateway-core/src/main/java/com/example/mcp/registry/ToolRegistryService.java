package com.example.mcp.registry;

import com.example.mcp.security.ToolAction;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 工具目录的应用服务：负责校验、生命周期字段与"MCP 侧同步"的编排。
 */
@Service
public class ToolRegistryService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> SCHEMA_TYPE = new TypeReference<>() {
    };

    private final ToolRegistryRepository repository;
    private final DynamicToolRegistry dynamicToolRegistry;

    public ToolRegistryService(ToolRegistryRepository repository,
                               @Lazy DynamicToolRegistry dynamicToolRegistry) {
        this.repository = repository;
        // @Lazy 打断构造环：SafeToolExecutor → 本类 → DynamicToolRegistry → SafeToolExecutor。
        // upsert/remove 在管理端请求时才真正触达 MCP 侧，届时 Bean 均已就绪。
        this.dynamicToolRegistry = dynamicToolRegistry;
    }

    @Transactional
    public ToolRegistry register(ToolRegistryRequest request) {
        String name = request.name();
        validate(name, request.inputSchema(), request.sideEffect());
        repository.findByName(name).ifPresent(existing -> {
            throw new IllegalArgumentException("工具已存在: " + name);
        });

        ToolRegistry tool = new ToolRegistry();
        applyRequest(tool, name, request);
        tool.setId(UUID.randomUUID());
        tool.setEnabled(request.enabled() == null || request.enabled());
        Instant now = Instant.now();
        tool.setCreatedAt(now);
        tool.setUpdatedAt(now);
        ToolRegistry saved = repository.save(tool);

        dynamicToolRegistry.upsert(saved);
        return saved;
    }

    public Optional<ToolRegistry> findByName(String name) {
        return repository.findByName(name);
    }

    public List<ToolRegistry> listEnabled() {
        return repository.findByEnabledTrue();
    }

    public List<ToolRegistry> listAll() {
        return repository.findAll();
    }

    @Transactional
    public Optional<ToolRegistry> update(String name, ToolRegistryRequest request) {
        validate(name, request.inputSchema(), request.sideEffect());
        if (request.name() != null && !request.name().equals(name)) {
            throw new IllegalArgumentException("请求体 name 与路径不一致: " + request.name() + " != " + name);
        }
        return repository.findByName(name).map(existing -> {
            applyRequest(existing, name, request);
            if (request.enabled() != null) {
                existing.setEnabled(request.enabled());
            }
            existing.setUpdatedAt(Instant.now());
            ToolRegistry saved = repository.save(existing);

            dynamicToolRegistry.upsert(saved);
            return saved;
        });
    }

    @Transactional
    public boolean delete(String name) {
        Optional<ToolRegistry> existing = repository.findByName(name);
        if (existing.isEmpty()) {
            return false;
        }
        repository.delete(existing.get());
        dynamicToolRegistry.remove(name);
        return true;
    }

    private void applyRequest(ToolRegistry tool, String name, ToolRegistryRequest request) {
        tool.setName(name);
        tool.setDescription(request.description());
        tool.setInputSchema(request.inputSchema());
        tool.setSideEffect(request.sideEffect());
        tool.setRequiredRole(request.requiredRole());
        tool.setRequiredTenant(request.requiredTenant());
        tool.setRateLimit(request.rateLimit());
    }

    private void validate(String name, String inputSchema, ToolAction sideEffect) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        if (sideEffect == null) {
            throw new IllegalArgumentException("sideEffect 不能为空（READ/WRITE/DANGEROUS）");
        }
        if (inputSchema == null || inputSchema.isBlank()) {
            throw new IllegalArgumentException("inputSchema 不能为空");
        }
        Map<String, Object> schema;
        try {
            schema = JSON.readValue(inputSchema, SCHEMA_TYPE);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("inputSchema 必须是合法的 JSON 对象", e);
        }
        if (!"object".equals(schema.get("type"))) {
            throw new IllegalArgumentException("inputSchema 根节点必须是 {\"type\":\"object\", ...}");
        }
    }
}
