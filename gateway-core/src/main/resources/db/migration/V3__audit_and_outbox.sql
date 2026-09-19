-- 阶段四：审计日志 + 自建轻量事务性 Outbox（Okapi 选型结论见 docs/architecture.md）
CREATE TABLE audit_log (
    id              UUID PRIMARY KEY,
    caller_id       VARCHAR(255) NOT NULL,
    tenant_id       VARCHAR(255) NOT NULL,
    tool_name       VARCHAR(255) NOT NULL,
    input           JSONB,
    output          JSONB,
    status          VARCHAR(20) NOT NULL,
    duration_ms     BIGINT,
    confirmation_id VARCHAR(64),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_audit_caller ON audit_log (caller_id);
CREATE INDEX idx_audit_tool ON audit_log (tool_name);
CREATE INDEX idx_audit_created ON audit_log (created_at);

CREATE TABLE outbox_message (
    id             UUID PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id   VARCHAR(64) NOT NULL,
    event_type     VARCHAR(64) NOT NULL,
    payload        JSONB NOT NULL,
    status         VARCHAR(10) NOT NULL,
    retry_count    INT NOT NULL DEFAULT 0,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at        TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_outbox_status ON outbox_message (status);
