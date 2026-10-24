package com.example.mcp.security;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 无状态 API Key 认证 + URL 级授权。
 * 未认证 → 401（HttpStatusEntryPoint）；已认证但权限不足 → 403（默认 AccessDeniedHandler）。
 * /confirmations/** 要求 confirmer 角色，与 AI 客户端凭据隔离。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeyStore apiKeyStore) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR, DispatcherType.FORWARD)
                        .permitAll()
                        .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                        .requestMatchers("/error").permitAll()
                        // OpenAPI 文档（仅接口元数据，业务端点仍在 /admin/** 与 /mcp/** 保护之内）
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // 确认台页面壳：浏览器导航带不了 X-API-Key 头，页面本身公开；
                        // 它渲染的全部数据来自 /confirmations/** 的 fetch 请求，仍要求 confirmer 角色
                        .requestMatchers("/confirmation-console.html").permitAll()
                        .requestMatchers("/admin/**").hasRole("admin")
                        .requestMatchers("/mcp/**").authenticated()
                        .requestMatchers("/confirmations/**").hasRole("confirmer")
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(new ApiKeyAuthFilter(apiKeyStore), UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
