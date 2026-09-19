package com.example.mcp.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从 application.yml 的 gateway.api-keys.keys.* 读取的内存 Key 存储，仅用于开发/测试。
 * record 构造器绑定（Boot 3+ 对 record 自动启用 constructor binding，无需 @ConstructorBinding）。
 */
@ConfigurationProperties(prefix = "gateway.api-keys")
public record InMemoryApiKeyStore(Map<String, ApiKeyEntry> keys) implements ApiKeyStore {

    public InMemoryApiKeyStore {
        keys = keys == null ? Map.of() : Map.copyOf(keys);
    }

    @Override
    public ApiKeyPrincipal findByKey(String key) {
        if (key == null) {
            return null;
        }
        ApiKeyEntry entry = keys.get(key);
        if (entry == null) {
            return null;
        }
        return new ApiKeyPrincipal(entry.callerId(), entry.tenantId(), Set.copyOf(entry.roles()));
    }

    public record ApiKeyEntry(String callerId, String tenantId, List<String> roles) {

        public ApiKeyEntry {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }
}
