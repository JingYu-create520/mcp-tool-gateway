# agent-demo — 可运行的 MCP demo agent

用一个**无 LLM 的固定状态机**充当 AI Agent，通过 Spring AI 的 MCP Client（Streamable HTTP）
连接本项目的 Gateway，完成一次带人工确认的真实任务，证明"AI 客户端 → 网关 → 后端工具"端到端可用。

## 前置：启动 Gateway

```bash
# 方式一（本机无 Docker，H2 内存库）
./mvnw spring-boot:run -pl gateway-core -Dspring-boot.run.profiles=h2

# 方式二（PostgreSQL，需要 docker compose up -d 先起库）
./mvnw spring-boot:run -pl gateway-core
```

Gateway 默认监听 `http://localhost:8080`，MCP 端点 `/mcp`。

## 启动 agent

新开一个终端：

```bash
./mvnw spring-boot:run -pl examples/agent-demo
```

agent 会在启动时连接 Gateway、跑完 5 步任务后自动退出。

## 预期运行日志

```text
[连接] MCP initialize 成功：protocol=2025-11-25，server=mcp-tool-gateway
[步骤1] vredis_search → {status=executed, result=...}
[步骤2] vredis_upsert → pending，confirmationId=5a3f1ee7-c698-...
[步骤3] confirm 5a3f1ee7-c698-... → HTTP 200 {"status":"CONFIRMED",...}
[步骤4] vredis_upsert(确认后) → {status=executed, confirmationId=5a3f1ee7-..., result=...}
[步骤5] 审计轨迹（3 条）: {"content":[{..."status":"PENDING"...},{..."status":"CONFIRMED"...},{..."status":"EXECUTED"...}],...}
======== Demo 完成: DemoResult[searchStatus=executed, upsertFirstStatus=pending, ...] ========
```

再开一个终端可以用审计 API 复核：

```bash
curl 'http://localhost:8080/admin/audit?toolName=vredis_upsert' -H 'X-API-Key: admin-key'
```

## 这个 demo 证明了什么

1. **协议互通**：一个标准的 MCP Client（Spring AI 生态的 MCP Java SDK 2.0，Streamable HTTP）能够
   完成 initialize 握手、tools/call 全流程，直接对接本网关——网关对任何合规 MCP 客户端开箱即用。
2. **安全策略对真实客户端生效**：WRITE 工具（vredis_upsert，admin 角色）在网关侧被强制进入
   pending → 人工确认 → 执行的状态机，确认后同输入重放才真正执行；全程带 X-API-Key 认证。
3. **审计完整可追溯**：Agent 的每一次调用（READ 的 EXECUTED、WRITE 的 PENDING/CONFIRMED/EXECUTED）
   都经事务性 Outbox 落入 audit_log，可通过 `/admin/audit` 查询复核。

## 连接配置

| 配置 | 默认值 | 说明 |
|---|---|---|
| `gateway.base-url` | http://localhost:8080 | Gateway 地址 |
| `gateway.api-key` | admin-key | demo 使用的 API Key（admin 角色） |

端到端集成测试 `AgentEndToEndIT` 会在同一 JVM 里把 Gateway（H2 + MockVredisServer）和
AgentRunner 一起拉起来跑完整流程，`./mvnw clean verify` 即可验证。
