package com.example.mcp.auth;

import com.example.mcp.security.ApiKeyPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 种子 Key 幂等语义：已存在的 Key（含被管理员禁用的）重启后不会被重新插入。
 * 回归背景：曾按 keyHash+enabled=true 查重，禁用的 Key 在重启后"复活"。
 */
@SpringBootTest
@ActiveProfiles("test")
class DbApiKeySeederIT {

    @Autowired
    DbApiKeySeeder seeder;

    @Autowired
    ApiKeyJpaRepository repository;

    @Autowired
    DbApiKeyStore store;

    @Test
    void disabledSeedKeyIsNotResurrected() {
        String userKeyHash = DbApiKeyStore.sha256("user-key");
        ApiKeyEntity banned = repository.findByKeyHash(userKeyHash).orElseThrow();
        banned.setEnabled(false);
        repository.save(banned);
        long before = repository.count();
        try {
            seeder.run(null);

            long after = repository.count();
            assertThat(repository.findByKeyHash(userKeyHash).orElseThrow().isEnabled()).isFalse();
            assertThat(after).isEqualTo(before);
        } finally {
            // 共享 H2 库：user-key 是同 JVM 其他测试的认证凭据，无论断言成败都必须恢复
            banned.setEnabled(true);
            repository.save(banned);
        }
    }

    @Test
    void seedIsIdempotentWhenAllKeysPresent() {
        long before = repository.count();
        seeder.run(null);
        assertThat(repository.count()).isEqualTo(before);
        assertThat(repository.findAll())
                .extracting(ApiKeyEntity::getCallerId)
                .contains("admin-user", "user-1");
        assertThat(repository.findAll())
                .allSatisfy(key -> assertThat(key.getRoles()).isNotBlank());
        List<String> hashes = repository.findAll().stream().map(ApiKeyEntity::getKeyHash).toList();
        assertThat(hashes).allSatisfy(hash -> assertThat(hash).hasSize(64));
    }

    @Test
    void duplicatedRolesInDbDoNotBreakAuthentication() {
        // roles 列由管理员手工维护、无约束：重复值曾让 Set.of 抛异常、该 Key 的所有请求 500
        ApiKeyEntity messy = new ApiKeyEntity();
        messy.setId(UUID.randomUUID());
        messy.setKeyHash(DbApiKeyStore.sha256("messy-key"));
        messy.setCallerId("messy-caller");
        messy.setTenantId("tenant-a");
        messy.setRoles("admin, admin,admin ,");
        messy.setEnabled(true);
        repository.save(messy);

        ApiKeyPrincipal principal = store.findByKey("messy-key");
        assertThat(principal).isNotNull();
        assertThat(principal.roles()).containsExactly("admin");
    }
}
