package com.example.upstream.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 内部演示服务：所有端点放行（网关接入认证由上游调用方自行处理）。
 * 显式声明链，避免 Boot 默认安全配置在联调类路径下把 MCP 端点锁死。
 */
@Configuration
@EnableWebSecurity
public class UpstreamSecurityConfig {

    @Bean
    public SecurityFilterChain upstreamSecurity(HttpSecurity http) throws Exception {
        return http
                .csrf(org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
