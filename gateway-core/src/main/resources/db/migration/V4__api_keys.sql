-- API Key 持久化：密钥只存 SHA-256 哈希，原始密钥不在数据库中。
-- key_hash 的 UNIQUE 约束自带唯一索引，认证查找走它，无需再建普通索引。
CREATE TABLE api_key (
    id          UUID PRIMARY KEY,
    key_hash    VARCHAR(64) UNIQUE NOT NULL,
    caller_id   VARCHAR(255) NOT NULL,
    tenant_id   VARCHAR(255) NOT NULL,
    roles       VARCHAR(500) NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE
);
