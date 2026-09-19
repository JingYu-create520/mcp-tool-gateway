# 架构文档（随阶段演进，当前为骨架版）

## 总览

```
AI Client ──MCP(Streamable HTTP)──▶ MCP Gateway
                                      │ 1. API Key 认证           （阶段二）
                                      │ 2. 权限策略检查             （阶段二）
                                      │ 3. 副作用分级 ToolAction     （阶段三执行）
                                      │ 4. 审计记录（Okapi Outbox）   （阶段四）
                                      ▼
                 READ ──────────────▶ 直通执行 ──▶ HTTP Adapter ──▶ vredis / 本地工具
                 WRITE / DANGEROUS ──▶ 创建 pending ──▶ 人工确认 ──▶ 执行 （阶段三）
```

## 模块职责

| 模块 | 职责 | 主要落地阶段 |
|---|---|---|
| gateway-core | Spring Boot 主应用；MCP Server 装配（Streamable HTTP）；工具注册；echo 自检工具 | 阶段一 |
| gateway-security | `ToolAction` 分级；API Key 认证；权限策略引擎；人工确认状态机（pending） | 阶段二/三 |
| gateway-audit | 事务性 Outbox 写入与投递；审计记录存储与查询 | 阶段四 |
| gateway-tools | vredis 工具封装；统一 HTTP Adapter（超时/重试/熔断） | 阶段五 |

## 关键设计决策

1. **MCP 传输**：Streamable HTTP。依赖 `spring-ai-starter-mcp-server-webmvc`，
   配置 `spring.ai.mcp.server.protocol=STREAMABLE`、`type=SYNC`，端点 `POST /mcp`。
   工具用 Spring AI 2.0 的 `@McpTool` / `@McpToolParam` 注解声明，注解扫描自动注册。

2. **人工确认 = pending 状态机 + 轮询**：Spring AI 2.0.1 捆绑的 MCP Java SDK 2.0.0 GA
   跟踪 MCP 2025-11-25 规范，不支持 MRTR 的 `InputRequiredResult` 重放。
   因此：WRITE/DANGEROUS 工具被调用时创建 pending 记录并立即返回
   "等待确认"结果；AI 客户端通过查询工具轮询结果；approve/reject 动作
   只能来自人工确认台（与 AI 客户端凭据隔离，见威胁模型 T4）。
   状态机：`PENDING → APPROVED / REJECTED / EXPIRED → EXECUTING → SUCCEEDED / FAILED`。

3. **审计走事务性 Outbox**（阶段四）：业务事务内写 outbox 表（PostgreSQL 16），
   保证"执行了工具"与"留下了审计"原子提交；后台任务投递落审计表，
   ShedLock 防多实例并发投递。选型 Okapi（SoftwareMill，Kotlin 库、可从 Java 调用）；
   若集成成本过高，备选 gruelbox/transaction-outbox —— 阶段四定稿。

4. **工具目录 = 数据库单一事实来源 + 动态注册**（阶段一落地）：
   `tool_registry` 表（inputSchema 落 JSONB）为工具目录的唯一事实来源；
   `DynamicToolRegistry` 启动时把 `enabled=true` 的工具注册进 MCP Server
   （`McpSyncServer.addTool`），`/admin/tools` 的注册/更新/删除实时联动
   （upsert/remove + `tools/list_changed` 通知）。注解声明的工具（echo）与
   数据库工具并存；`ToolAction` 统一映射 MCP 注解 hints
   （READ→readOnly=true，WRITE→destructive=false，DANGEROUS→destructive=true）。
   工具执行当前为桩实现，阶段五由 HTTP Adapter 替换。

5. **后端统一 HTTP Adapter**：网关不直连 vredis 私有协议，只经 HTTP 调用，
   便于集中做超时、重试、熔断与沙箱化。

6. **默认拒绝（deny by default）**：未注册、未分级、无策略命中的工具调用一律 DENY。

7. **数据库双轨**：默认 profile = PostgreSQL 16 + Flyway（`V1__init.sql`，JSONB）；
   `h2` profile = 本地无 Docker 兜底（Flyway 关闭，Hibernate 按方言建表）。
   集成测试同理：H2 版必跑，Testcontainers + PG 版无 Docker 自动跳过、CI 必跑。

8. **认证与权限的两层模型**（阶段二落地）：
   - **URL 层**（SecurityConfig，Spring Security 7）：`/admin/**` 需 `admin` 角色，
     `/mcp/**`、`/confirmations/**` 需认证（任意有效 Key）；未认证统一 401
     （显式 `HttpStatusEntryPoint`，Spring Security 默认是 403，必须覆盖），
     已认证权限不足 403。
   - **API Key 认证**：`ApiKeyAuthFilter` 从 `X-API-Key` 头解出身份写入
     SecurityContext（principal = CallerContext，authorities = ROLE_*）；
     无 Key/无效 Key 静默放行，由授权层统一裁决。Key 存储当前为
     application.yml 内存实现（开发/测试），生产换数据库 + 哈希。
   - **工具层**（PermissionChecker）：调用工具时校验 registry 行上的
     `requiredRole` / `requiredTenant`；MCP 调用内拒绝不是 HTTP 状态码，
     而是返回 `isError=true` + `permission_denied` 前缀的工具结果（MCP 语义），
     REST 侧则由 SecurityExceptionHandler 映射 403。

9. **人工确认 = pending + 轮询**（阶段三落地）：
   WRITE/DANGEROUS 工具统一经 `SafeToolExecutor` 执行：READ 直通；
   WRITE/DANGEROUS 创建 pending（默认 30 分钟过期，`input` 存快照 + SHA-256 指纹）。
   状态机 `PENDING → CONFIRMED / REJECTED / EXPIRED`，`CONFIRMED → EXECUTED`；
   confirm/reject **幂等**（非法前置不报错，返回当前状态），乐观锁防并发改坏。
   确认入口两个：REST `/confirmations/{id}/confirm|reject` 与
   MCP 工具 `get_confirmation_status`（AI 客户端轮询查询）。
   执行"已确认"请求时匹配同工具+同调用方+同输入指纹的 CONFIRMED 记录，
   并【使用确认时保存的输入】执行（防确认后换参攻击）。
   过期扫描 `ExpirationScheduler`（5 分钟一轮）由 ShedLock
   （`JdbcTemplateLockProvider` + `usingDbTime`）做分布式互斥。

10. **审计 = 事务性 Outbox + 异步投递**（阶段四落地）：
    - **选型记录**：评估过 Okapi（SoftwareMill）——其模块阵容为 okapi-kafka/okapi-http/
      okapi-exposed，是面向 **Kafka/HTTP 外部投递**的消息型 outbox，与本项目
      "relay 落本地 audit_log 表"的目标不匹配；且自带 Liquibase 系 schema 管理，
      与 Flyway 双迁移工具有冲突风险。**结论：方案 B，自建轻量 Outbox**
      （`outbox_message` 表 + `AuditEventPublisher` + `OutboxRelay`，约 200 行），
      备选 gruelbox/transaction-outbox 仍可作为后续升级项。
    - **发布**：`AuditEventPublisher.publish`（REQUIRED 传播）——调用方在事务内
      （ConfirmationService 的五个状态转换）时与业务**原子提交**，保证审计不丢；
      无事务场景（READ 直通、DENIED）独立提交。脱敏（Sanitizer）在写 outbox 前完成。
    - **投递**：`OutboxRelay` 每 5 秒扫描 PENDING（ShedLock 互斥），写入 audit_log
      后标记 SENT；失败重试 3 次后 FAILED。audit_log 主键 = 事件 id（赋值式主键 +
      `Persistable.isNew()=true`），并发重复投递由主键冲突去重 —— **at-least-once + 幂等消费**。
    - **接入点**：ConfirmationService（PENDING/CONFIRMED/REJECTED/EXECUTED/EXPIRED）、
      SafeToolExecutor（READ 的 EXECUTED 带 durationMs、DENIED）、
      注解工具（echo、get_confirmation_status 经 ToolCallAuditor 直接发布）。
    - **查询**：`GET /admin/audit`（admin 角色），callerId/toolName/status/from/to
      动态过滤 + 分页（JpaSpecificationExecutor）。

## 端点

| 端点 | 说明 |
|---|---|
| POST /mcp | MCP Streamable HTTP，需认证（X-API-Key）；当前工具：`echo`、`vredis_search`（桩实现）、`get_confirmation_status` |
| /admin/tools | 工具目录 CRUD，需 admin 角色；实时联动 MCP Server |
| /confirmations/** | 人工确认入口：`{id}/confirm`、`{id}/reject`、`{id}`（查询），需认证 |
| /admin/audit | 审计查询：callerId/toolName/status/from/to 过滤 + 分页，需 admin 角色 |

## ADR 决策记录（编号沿用项目惯例，001–005 对应上文决策 1–5 的早期定稿）

- **ADR-006 放弃 Okapi，自建轻量 Outbox**：侦察 Maven Central 确认 com.softwaremill.okapi
  是面向 Kafka/HTTP 外部投递的消息型 outbox（okapi-kafka/okapi-http 模块），且自带
  Liquibase 系 schema 管理，与"relay 落本地 audit_log 表 + Flyway"两个前提都不匹配。
  自建 `outbox_message` + Publisher（REQUIRED 同事务）+ Relay（ShedLock 互斥、重试 3 次），
  at-least-once + 幂等消费（audit_log 主键 = 事件 id，主键冲突视为已投递）。
- **ADR-007 vredis 工具走 SafeToolExecutor 链路，不走注解**：注解工具（@McpTool 扫描）
  直接注册进 MCP Server，会绕过 SafeToolExecutor 的 WRITE/DANGEROUS 人工确认分支与
  工具级权限检查。vredis 四工具由 gateway-tools 以 ToolExecutorResolver SPI 声明
  （定义 + 执行器），gateway-core 的 ExternalToolRegistrar（@Order(0)，先于注册器）入库
  并绑定执行器，DynamicToolRegistry（@Order(10)）统一注册；管理端自定义工具无执行器时
  回退到内置桩。gateway-tools 反向依赖 core 会造成模块环，故走 SPI。
- **ADR-008 AuditEvent.id 用赋值式主键**：Hibernate 7 对带 @GeneratedValue 的实体
  persist 预设 id 会判定 detached（"Detached entity passed to persist"），
  而 relay 需要"主键 = 事件 id 的幂等 INSERT"。去掉生成器、由发布方生成 id，
  配合 Persistable.isNew()=true 走纯 INSERT 路径。
- **ADR-009 input_hash 机制**：JSONB 会规范化 JSON（键序/空白），落库后的 input 无法
  与新输入做可靠字符串等值匹配；且"确认后换参"是真实攻击面。pending 记录保存
  输入快照 + SHA-256 指纹，执行"已确认"请求按（tool, caller, input_hash）匹配，
  并强制使用**确认时保存的输入**执行。

- **ADR-010 联邦工具前缀命名**：upstream 工具以 `{upstreamName}__{toolName}`（双下划线）
  重命名后并入统一工具清单，避免跨 upstream 的命名冲突；本地工具名不含双下划线，
  路由歧义为零，且 AI 端能从名称直接辨识工具来源。
- **ADR-011 选择 Resilience4j（core + spring-boot3 模块）**：熔断与超时隔离是 upstream
  联邦的硬需求。选用 Resilience4j 2.4.0——CircuitBreaker/TimeLimiter 双层包裹
  （超时在内、熔断在外，超时计为失败），配置走官方 spring-boot3 模块的 YAML 约定；
  若未来该模块与 Boot 大版本不兼容，core 模块无框架耦合可独立保留。
- **ADR-012 本地与联邦混合注册模式**：不改 DynamicToolRegistry 的本地注册机制，
  upstream 作为"额外工具源"——FederatedToolRegistry 启动时与每轮健康检查后把
  `{upstream}__{tool}` 经新增的 `registerExternalTool` 注册进同一 MCP 清单并落
  ToolRegistry（sideEffect=READ，requiredRole 按 ADR 权限映射），执行器绑定到
  UpstreamToolRouter；upstream 不可达时其工具自动从 tools/list 剔除。权限检查、
  审计（Outbox）、admin 查询对联邦工具与本地工具一视同仁。

## 模块依赖

```
gateway-core ──▶ gateway-audit（审计 SPI：Publisher/Relay/Controller）
     │      ──▶ gateway-security（ToolAction/CallerContext/SecurityConfig/**）
     │      ──▶ gateway-tools（ToolExecutorResolver SPI，运行时收集实现）
     │                └──▶ gateway-security
     └── 含 split package：com.example.mcp.security 的 PermissionChecker（见技术债）
```

## 模块依赖的已知技术债（2026-09-19 阶段三前梳理，阶段五复核仍成立）

- **PermissionChecker 的 split package**：物理位于 gateway-core（包名仍是
  com.example.mcp.security，与 gateway-security 构成 split package）。
  原因：`check()` 需要 ToolRegistry，移入 gateway-security 会形成
  Maven reactor 循环依赖。影响面评估：全项目仅此一处跨模块 split package；
  阶段三新增的 confirmation 包整体放在 gateway-core，依赖面没有扩大。
  若未来 core 需要反向依赖更多 security 类型，再引入 ToolDescriptor 接口方案。
- **CONFIRMED → EXECUTED 的并发加固**：SafeToolExecutor 命中 CONFIRMED 记录后执行，
  `@Version` 乐观锁保证状态一致，但不保证 actualExecutor 恰好执行一次
  （并发双调用理论上可重复执行）。单实例部署风险低；多实例加固需要原子 claim
  （`UPDATE ... SET status='EXECUTED' WHERE status='CONFIRMED'` 检查影响行数）。
- **shedlock 表双路建表**：PostgreSQL 由 V2 迁移建表；H2（Flyway 关闭）由
  ShedLockConfig 的 InitializingBean 用 `CREATE TABLE IF NOT EXISTS` 兜底。
  两边幂等不冲突，但属于迁移之外的代码建表；若后续做 vendor 分目录迁移可收敛。
