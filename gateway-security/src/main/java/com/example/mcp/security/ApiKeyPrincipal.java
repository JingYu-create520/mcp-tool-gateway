package com.example.mcp.security;

import java.util.Set;

/**
 * API Key 对应的身份快照；ApiKeyStore 返回它，过滤器再转成 CallerContext。
 */
public record ApiKeyPrincipal(String callerId, String tenantId, Set<String> roles) {
}
