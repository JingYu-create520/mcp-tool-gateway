package com.example.mcp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 从 X-API-Key 头解析调用方身份并写入 SecurityContext（principal 为 CallerContext，
 * authorities 为 ROLE_ + role）。无 Key 或 Key 无效时静默放行，由授权规则决定
 * 401（未认证）或 403（权限不足），过滤器自身不做拦截决策。
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final ApiKeyStore apiKeyStore;

    public ApiKeyAuthFilter(ApiKeyStore apiKeyStore) {
        this.apiKeyStore = apiKeyStore;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String key = request.getHeader(HEADER);
            if (key != null && !key.isBlank()) {
                ApiKeyPrincipal principal = apiKeyStore.findByKey(key);
                if (principal != null) {
                    CallerContext caller = new CallerContext(
                            principal.callerId(), principal.tenantId(), principal.roles());
                    List<SimpleGrantedAuthority> authorities = principal.roles().stream()
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                            .toList();
                    Authentication authentication =
                            new UsernamePasswordAuthenticationToken(caller, key, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
