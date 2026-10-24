package com.example.mcp.auth;

import com.example.mcp.security.ApiKeyPrincipal;
import com.example.mcp.security.ApiKeyStore;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 数据库驱动的 API Key 存储。
 *
 * <p>认证流程：请求中的原始密钥 → SHA-256 哈希 → 按 keyHash 查库 → 匹配则返回身份。
 * 数据库中只存哈希，原始密钥不落盘（安全要求）。
 * 种子密钥由 {@link DbApiKeySeeder} 在启动时写入。
 */
@Component
public class DbApiKeyStore implements ApiKeyStore {

    private final ApiKeyJpaRepository repository;

    public DbApiKeyStore(ApiKeyJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public ApiKeyPrincipal findByKey(String key) {
        String keyHash = sha256(key);
        return repository.findByKeyHashAndEnabled(keyHash, true)
                .map(entity -> new ApiKeyPrincipal(
                        entity.getCallerId(),
                        entity.getTenantId(),
                        // 手工维护的 roles 列没有约束：去重（Set.of 遇重复会抛异常把认证变 500）、滤空、容忍空格
                        normalizeRoles(entity.getRoles())))
                .orElse(null);
    }

    private static Set<String> normalizeRoles(String roles) {
        return Arrays.stream(roles.split("\\s*,\\s*"))
                .filter(role -> !role.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
