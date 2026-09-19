-- 阶段三：人工确认（pending + 轮询）+ ShedLock 分布式调度互斥。
-- H2 测试/本地 profile 关闭 Flyway：pending_execution 由 Hibernate 按方言建表，
-- shedlock 表由 ShedLockConfig 的初始化 Bean 用 CREATE TABLE IF NOT EXISTS 兜底。
CREATE TABLE pending_execution (
    id              UUID PRIMARY KEY,
    confirmation_id UUID NOT NULL UNIQUE,
    tool_name       VARCHAR(255) NOT NULL,
    caller_id       VARCHAR(255) NOT NULL,
    tenant_id       VARCHAR(255) NOT NULL,
    input           JSONB NOT NULL,
    input_hash      VARCHAR(64) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    result          JSONB,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    confirmed_at    TIMESTAMP WITH TIME ZONE,
    executed_at     TIMESTAMP WITH TIME ZONE,
    version         BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_pending_status ON pending_execution (status);
-- SafeToolExecutor 查找"已确认待执行"记录用
CREATE INDEX idx_pending_claim ON pending_execution (tool_name, caller_id, input_hash, status);

CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
