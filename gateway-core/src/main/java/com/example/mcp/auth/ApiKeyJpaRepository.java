package com.example.mcp.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ApiKeyJpaRepository extends JpaRepository<ApiKeyEntity, UUID> {

    Optional<ApiKeyEntity> findByKeyHashAndEnabled(String keyHash, boolean enabled);

    /** 不筛 enabled：种子的幂等判断必须包含被禁用的 Key，否则禁用会在重启后"复活"。 */
    Optional<ApiKeyEntity> findByKeyHash(String keyHash);

    /** 禁用端点按调用方定位（替代 findAll 全表过滤）。 */
    java.util.List<ApiKeyEntity> findByCallerId(String callerId);
}
