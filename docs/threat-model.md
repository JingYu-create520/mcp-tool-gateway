# 威胁模型（STRIDE 完整版）

## 资产清单

| # | 资产 | 说明 |
|---|---|---|
| A1 | API Key | 网关唯一凭据体系（X-API-Key），失守 = 身份失守 |
| A2 | vredis 数据 | 可被 upsert 篡改、delete 清除的业务数据 |
| A3 | 审计日志 | 事后追责的唯一证据链，必须不丢、不可静默改写 |
| A4 | 确认记录（confirmationId） | "确认令牌"：被伪造即可绕过人工把关 |

## 信任边界

```
AI Client（不可信：提示注入可诱导任意工具调用）┃ Gateway ┃ vredis / 人工确认台
```

网关必须假设"AI 会尝试调用一切它能调用的工具，并尝试伪造/重放确认"。

## STRIDE 分析

### S — 仿冒（Spoofing）

| # | 威胁 | 攻击路径 | 防护 | 审计查询 |
|---|---|---|---|---|
| S1 | 无凭据调用工具 | 直接 POST /mcp 不带 X-API-Key | ApiKeyAuthFilter + `authenticated()` → 401（显式 HttpStatusEntryPoint） | `?callerId=unknown`（无法认证者不会产生审计；401 由访问日志兜底） |
| S2 | 伪造/盗用 Key | 猜测或重放他人 Key | Key 只存 SHA-256 哈希（api_key 表，V4 迁移）；查库认证，泄露明文也无法反推库内凭据；发钥/禁钥走 /admin/api-keys（admin 角色，明文仅创建时返回一次，KEY_ISSUED/KEY_DISABLED 全程留痕——失窃后可按审计定位发钥人与时间）；Key→callerId 绑定审计 | `?callerId=<被盗身份>&from=<失窃时间>` 比对行为基线；`?status=KEY_ISSUED` 追溯发钥链 |
| S3 | 伪造人工确认 | AI 拿自己的 Key 调 /confirmations/{id}/confirm | URL 层 hasRole(confirmer)：user-key 不含 confirmer 角色 → 403；admin-key 含 confirmer 供运维确认台使用；pending 另有 callerId + input_hash 绑定兜底 | `?status=CONFIRMED` 全量留痕，可人工复核 |

### T — 篡改（Tampering）

| # | 威胁 | 攻击路径 | 防护 | 审计查询 |
|---|---|---|---|---|
| T1 | 确认后换参 | confirm {k:1} 后用 {k:恶意} 调用 | input SHA-256 指纹匹配 + 执行时使用**确认时保存的输入** | `?toolName=vredis_upsert&status=EXECUTED` 的 input 与 PENDING 快照比对 |
| T2 | 删改审计 | 直接改库/调接口删记录 | 应用层无任何删除/更新审计的接口；Outbox 与业务同事务落库；工具目录的注册/更新/删除同样留痕（TOOL_REGISTERED/UPDATED/DELETED） | 依赖 DB 权限管控（生产环境应用账号不授 DELETE/UPDATE on audit_log） |
| T3 | 篡改 pending 状态 | 重复 confirm/reject 翻转状态 | 状态机单向 + 幂等 + @Version 乐观锁 | `?status=EXPIRED` 等全转换留痕 |

### R — 抵赖（Repudiation）

| # | 威胁 | 攻击路径 | 防护 | 审计查询 |
|---|---|---|---|---|
| R1 | 否认执行过危险操作 | "我没删数据" | 每次调用完整轨迹：callerId/tenantId/toolName/input/output/status/confirmationId/耗时，经 Outbox 保证不丢 | `?toolName=vredis_delete` 全链（PENDING→CONFIRMED→EXECUTED 或 REJECTED） |

### I — 信息泄露（Information Disclosure）

| # | 威胁 | 攻击路径 | 防护 | 审计查询 |
|---|---|---|---|---|
| I1 | 跨租户读取 | 用 A 租户 Key 读 B 租户键 | requiredTenant 已启用：vredis 工具限定 tenant-a，其他租户调用方一律拒绝（PermissionChecker） | 按 tenantId 过滤异常访问 |
| I2 | 敏感值进入审计 | input 里带密码/令牌 | Sanitizer：password/token/apiKey/secret/authorization（含大小写与下划线变体）→ *** | 抽查 `?toolName=echo` 的 input 字段 |

### D — 拒绝服务（Denial of Service）

| # | 威胁 | 攻击路径 | 防护 | 审计查询 |
|---|---|---|---|---|
| D1 | 刷 /mcp 制造 pending 堆积 | 高频调用 WRITE 工具 | pending 30 分钟过期（ShedLock 互斥扫描）；ToolRateLimiter 强制限流（注册时配置 rateLimit，tool+caller 每 60 秒计数，超限 → rate_limited 错误 + RATE_LIMITED 审计） | `?status=PENDING` 数量监控 |

### E — 越权（Elevation of Privilege）

| # | 威胁 | 攻击路径 | 防护 | 审计查询 |
|---|---|---|---|---|
| E1 | user 调 admin 工具 | 提示注入诱导 | 工具级 requiredRole（PermissionChecker）→ isError 结果 + DENIED 审计 | `?status=DENIED` |
| E2 | user 调 /admin/** | 直接访问管理端 | URL 层 hasRole(admin) → 403 | 访问日志 |
| E3 | AI 自我提权确认 | AI 拿同 Key 确认自己的 pending | 确认台凭据隔离：/confirmations/** 仅 confirmer 角色（user-key 不含）；callerId + input_hash 绑定兜底。种子凭据公开可知，暴露在不可信网络的部署必须 `gateway.seed-api-keys=false` 并自行发钥 | `?status=CONFIRMED` 与确认时间差分析 |

## 工具威胁矩阵

| 工具 | 副作用 | requiredRole | 主要威胁 | 防护 |
|---|---|---|---|---|
| echo | READ | —（自检） | 信息泄露（无敏感数据，风险极低） | 只读 + 审计 |
| get_confirmation_status | READ | —（轮询查询） | pending 探测 | 只读 + 审计 |
| vredis_search | READ | user | 信息泄露（跨租户） | 租户隔离（requiredTenant=tenant-a）+ 审计 |
| vredis_stats | READ | user | 信息泄露（容量探测） | 同上 |
| vredis_upsert | WRITE | admin | 数据篡改、AI 伪造确认 | admin + 人工确认 + input_hash + 审计 |
| vredis_delete | DANGEROUS | admin | 数据丢失、AI 伪造确认 | admin + 人工确认 + input_hash + 审计（DANGEROUS 与 WRITE 同路径：pending + 人工确认） |

## 已知限制（诚实清单）

- **L1** MCP 2025-11-25 规范下的 SDK 2.0.0 不支持 MRTR / InputRequiredResult 重放与 elicitation → 人工确认只能 pending + 轮询。
- **L2** 执行语义是"至少一次"：原子 claim（DB 条件 UPDATE，同时复查确认有效期）保证同一条
  确认记录只被一个请求执行；执行失败退回 CONFIRMED 后可重试，失败留 EXECUTION_FAILED 审计。
  upstream 调用仅在"请求必然未执行"（会话失效）时重试，超时/IO 失败不重发。
  工具实现仍需幂等（vredis upsert/delete 天然幂等）。
- **L3** 审计未做密码学防篡改（WORM/哈希链）；依赖 DB 权限 + 追加式访问模式。
- **L4** 限流默认为单实例内存固定窗口（gateway.rate-limiter=local）；多实例部署时切换
  `gateway.rate-limiter=redis`（compose 已含 Redis）即获得跨实例精确配额——残余限制：
  Redis 故障时限流器不可用（调用返回 isError，不静默放行）。
- **L5** audit_log 无自动归档/保留策略（append-only 设计）：容量规划与归档节奏属部署侧决策；
  索引与分页保证查询性能不随表规模退化（压测基线在案），Outbox 积压有 gauge 与告警。
- **L6** 联邦工具副作用按名字启发式分级：名字不含副作用关键词的写工具（如 put_data）
  会被判 READ 直通；启发式只覆盖命名惯例，接入不可信 upstream 前必须人工核对工具清单。
- **L7** 工具结果包含原始用户输入（探索实测：echo 会原样回显含 `<script>` 的文本，
  传输层 JSON 转义正确、MCP 客户端按文本解析安全）：**任何把工具结果渲染为 HTML 的
  消费者必须自行转义**——确认台已示范（escapeHtml 全覆盖）；传输层不做 HTML 转义以保
  证回显语义保真。
- **L8** 工具入参无长度上限（实测 1MB message 正常回显，98ms）：单请求体积依赖传输层
  上限（Spring 默认 2MB POST 限制），更严格的每字段限额属部署侧决策（需与业务字段
  语义对齐，网关侧一刀切可能破坏合法大参数）。
