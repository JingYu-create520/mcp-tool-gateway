package com.example.mcp.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 启动时写入种子 API Key（如果不存在）。种子凭据是公开可知的（就在仓库里），
 * 任何暴露在不可信网络上的部署都应设 gateway.seed-api-keys=false 关闭本组件，
 * 并用管理工具生成密钥写入 api_key 表。
 */
@Component
@ConditionalOnProperty(name = "gateway.seed-api-keys", havingValue = "true", matchIfMissing = true)
public class DbApiKeySeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DbApiKeySeeder.class);

    private final ApiKeyJpaRepository repository;

    public DbApiKeySeeder(ApiKeyJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed("admin-key", "admin-user", "tenant-a", "admin,user,confirmer");
        seed("user-key", "user-1", "tenant-a", "user");
    }

    private void seed(String rawKey, String callerId, String tenantId, String roles) {
        String keyHash = DbApiKeyStore.sha256(rawKey);
        // 不筛 enabled：已被管理员禁用的种子 Key 重启后不能复活
        if (repository.findByKeyHash(keyHash).isPresent()) {
            return;
        }
        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setId(UUID.randomUUID());
        entity.setKeyHash(keyHash);
        entity.setCallerId(callerId);
        entity.setTenantId(tenantId);
        entity.setRoles(roles);
        entity.setEnabled(true);
        repository.save(entity);
        log.info("已写入种子 API Key: callerId={}, roles={}", callerId, roles);
    }
}
