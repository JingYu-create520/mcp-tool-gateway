package com.example.mcp.auth;

import com.example.mcp.audit.AuditEvent;
import com.example.mcp.audit.AuditEventPublisher;
import com.example.mcp.core.tool.ToolCallAuditor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API Key 管理端（URL 层 hasRole(admin)）。
 * 关闭种子（gateway.seed-api-keys=false）后的发钥手段：此前只能手工 SQL，
 * 安全建议与实际能力脱节。明文密钥仅在创建响应中出现一次，库内只存 SHA-256 哈希；
 * 删除为逻辑禁用（enabled=false），行保留以防种子查重把禁用 Key 复活。
 */
@Tag(name = "API Key 管理", description = "凭据的发放与禁用（明文只在创建时返回一次）")
@RestController
@RequestMapping("/admin/api-keys")
public class ApiKeyAdminController {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyJpaRepository repository;
    private final AuditEventPublisher auditPublisher;
    private final ToolCallAuditor auditor;

    public ApiKeyAdminController(ApiKeyJpaRepository repository, AuditEventPublisher auditPublisher,
                                 ToolCallAuditor auditor) {
        this.repository = repository;
        this.auditPublisher = auditPublisher;
        this.auditor = auditor;
    }

    public record CreateKeyRequest(
            @io.swagger.v3.oas.annotations.media.Schema(description = "调用方标识，认证后写入审计的 callerId", example = "agent-prod-1")
            String callerId,
            @io.swagger.v3.oas.annotations.media.Schema(description = "租户标识", example = "tenant-a")
            String tenantId,
            @io.swagger.v3.oas.annotations.media.Schema(description = "逗号分隔的角色列表", example = "user")
            String roles) {
    }

    /** 绝不暴露 keyHash；明文 key 仅创建响应一次性携带。 */
    public record KeyRow(UUID id, String callerId, String tenantId, String roles, boolean enabled) {
        static KeyRow from(ApiKeyEntity e) {
            return new KeyRow(e.getId(), e.getCallerId(), e.getTenantId(), e.getRoles(), e.isEnabled());
        }
    }

    @Operation(summary = "发放新 Key", description = "生成随机密钥并落哈希；明文仅在本次响应返回，请立即分发保存")
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateKeyRequest request) {
        if (request.callerId() == null || request.callerId().isBlank()
                || request.tenantId() == null || request.tenantId().isBlank()
                || request.roles() == null || request.roles().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "bad_request", "message", "callerId/tenantId/roles 均必填"));
        }
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String plaintext = "mcp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setId(UUID.randomUUID());
        entity.setKeyHash(DbApiKeyStore.sha256(plaintext));
        entity.setCallerId(request.callerId().trim());
        entity.setTenantId(request.tenantId().trim());
        entity.setRoles(request.roles().trim());
        entity.setEnabled(true);
        repository.save(entity);
        // 发钥是最高敏感度管理操作（直接扩大认证面），必须留痕；审计只记元数据，绝不含明文/哈希
        audit("KEY_ISSUED", entity.getCallerId(),
                "{\"callerId\":\"" + entity.getCallerId() + "\",\"tenantId\":\"" + entity.getTenantId()
                        + "\",\"roles\":\"" + entity.getRoles() + "\"}");

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "apiKey", plaintext,
                "callerId", entity.getCallerId(),
                "tenantId", entity.getTenantId(),
                "roles", entity.getRoles()));
    }

    @Operation(summary = "Key 列表", description = "不含哈希与明文")
    @GetMapping
    public List<KeyRow> list() {
        return repository.findAll().stream().map(KeyRow::from).toList();
    }

    @Operation(summary = "禁用 Key", description = "逻辑禁用（行保留，防止种子查重复活）；立即生效于认证")
    @DeleteMapping("/{callerId}")
    public ResponseEntity<Map<String, String>> disable(@PathVariable String callerId) {
        List<ApiKeyEntity> matches = repository.findByCallerId(callerId);
        if (matches.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "not_found", "message", "callerId 不存在: " + callerId));
        }
        matches.forEach(e -> {
            e.setEnabled(false);
            repository.save(e);
        });
        audit("KEY_DISABLED", callerId, null);
        return ResponseEntity.noContent().build();
    }

    /**
     * 审计事件无"主体"专用字段：被操作的调用方记在 toolName（按 ?toolName= 或 ?status= 可查），
     * callerId 列记录的是执行管理动作的操作者（与工具调用审计的 caller 语义一致）。
     */
    private void audit(String action, String subject, String detail) {
        AuditEvent event = auditor.event(subject, detail);
        event.setStatus(action);
        auditPublisher.publish(event);
    }
}
