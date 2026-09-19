package com.example.mcp.security;

/**
 * API Key → 身份 的查询接口；key 无效时返回 null，由过滤器决定放行策略。
 * 当前实现为 InMemoryApiKeyStore（开发/测试用）；后续可替换为数据库实现
 * （密钥只存哈希，见威胁模型 T1）。
 */
public interface ApiKeyStore {

    ApiKeyPrincipal findByKey(String key);
}
