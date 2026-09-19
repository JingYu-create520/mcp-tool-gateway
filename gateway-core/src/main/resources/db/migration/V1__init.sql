-- 工具注册表：网关对外暴露的工具目录。
-- PostgreSQL 专用迁移；H2 测试环境（application-h2.yml）由 Hibernate 按方言生成等价结构。
CREATE TABLE tool_registry (
    id              UUID PRIMARY KEY,
    name            VARCHAR(255) NOT NULL UNIQUE,
    description     VARCHAR(1024),
    input_schema    JSONB NOT NULL,
    side_effect     VARCHAR(20) NOT NULL,
    required_role   VARCHAR(100),
    required_tenant VARCHAR(100),
    rate_limit      INTEGER,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL
);
