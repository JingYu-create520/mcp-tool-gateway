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
| S2 | 伪造/盗用 Key | 猜测或重放他人 Key | Key 仅存于服务端配置（生产须换 DB+哈希）；Key→callerId 绑定审计 | `?callerId=<被盗身份>&from=<失窃时间>` 比对行为基线 |
| S3 | 伪造人工确认 | AI 拿自己的 Key 调 /confirmations/{id}/confirm | 见"E3 / 已知限制 L3"：pending 与 callerId + input_hash 绑定，确认后必须**同调用方+同输入**再调用才执行；建议部署时用反向代理把 /confirmations 限制到内网确认台 | `?status=CONFIRMED` 全量留痕，可人工复核 |

### T — 篡改（Tampering）

| # | 威胁 | 攻击路径 | 防护 | 审计查询 |
|---|---|---|---|---|
| T1 | 确认后换参 | confirm {k:1} 后用 {k:恶意} 调用 | input SHA-256 指纹匹配 + 执行时使用**确认时保存的输入** | `?toolName=vredis_upsert&status=EXECUTED` 的 input 与 PENDING 快照比对 |
| T2 | 删改审计 | 直接改库/调接口删记录 | 应用层无任何删除/更新审计的接口；Outbox 与业务同事务落库 | 依赖 DB 权限管控（应用账号不授 DELETE/UPDATE on audit_log，见部署清单） |
| T3 | 篡改 pending 状态 | 重复 confirm/reject 翻转状态 | 状态机单向 + 幂等 + @Version 乐观锁 | `?status=EXPIRED` 等全转换留痕 |

### R — 抵赖（Repudiation）

| # | 威胁 | 攻击路径 | 防护 | 审计查询 |
|---|---|---|---|---|
| R1 | 否认执行过危险操作 | "我没删数据" | 每次调用完整轨迹：callerId/tenantId/toolName/input/output/status/confirmationId/耗时，经 Outbox 保证不丢 | `?toolName=vredis_delete` 全链（PENDING→CONFIRMED→EXECUTED 或 REJECTED） |

### I — 信息泄露（Information Disclosure）

| # | 威胁 | 攻击路径 | 防护 | 审计查询 |
|---|---|---|---|---|
| I1 | 跨租户读取 | 用 A 租户 Key 读 B 租户键 | requiredTenant 机制已实现（当前 vredis 工具为 null=不限制，见已知限制 L4） | 按 tenantId 过滤异常访问 |
| I2 | 敏感值进入审计 | input 里带密码/令牌 | Sanitizer：password/token/apiKey/secret/authorization（含大小写与下划线变体）→ *** | 抽查 `?toolName=echo` 的 input 字段 |

### D — 拒绝服务（Denial of Service）

| # | 威胁 | 攻击路径 | 防护 | 审计查询 |
|---|---|---|---|---|
| D1 | 刷 /mcp 制造 pending 堆积 | 高频调用 WRITE 工具 | pending 30 分钟过期（ShedLock 互斥扫描）；rate_limit 字段已预留（**强制限流未实现，见 L5**） | `?status=PENDING` 数量监控 |

### E — 越权（Elevation of Privilege）

| # | 威胁 | 攻击路径 | 防护 | 审计查询 |
|---|---|---|---|---|
| E1 | user 调 admin 工具 | 提示注入诱导 | 工具级 requiredRole（PermissionChecker）→ isError 结果 + DENIED 审计 | `?status=DENIED` |
| E2 | user 调 /admin/** | 直接访问管理端 | URL 层 hasRole(admin) → 403 | 访问日志 |
| E3 | AI 自我提权确认 | AI 拿同 Key 确认自己的 pending | 同 S3：callerId + input_hash 绑定；确认台凭据隔离为 v1 已知限制（L3） | `?status=CONFIRMED` 与确认时间差分析 |

## 工具威胁矩阵

| 工具 | 副作用 | requiredRole | 主要威胁 | 防护 |
|---|---|---|---|---|
| echo | READ | —（自检） | 信息泄露（无敏感数据，风险极低） | 只读 + 审计 |
| get_confirmation_status | READ | —（轮询查询） | pending 探测 | 只读 + 审计 |
| vredis_search | READ | user | 信息泄露（跨租户） | 租户隔离（requiredTenant，见 L4）+ 审计 |
| vredis_stats | READ | user | 信息泄露（容量探测） | 同上 |
| vredis_upsert | WRITE | admin | 数据篡改、AI 伪造确认 | admin + 人工确认 + input_hash + 审计 |
| vredis_delete | DANGEROUS | admin | 数据丢失、AI 伪造确认 | admin + 人工确认 + input_hash + 审计（DANGEROUS 默认拒绝、显式放行语义见权限矩阵） |

## 已知限制（v1，诚实清单）

- **L1** MCP 2025-11-25 规范下的 SDK 2.0.0 不支持 MRTR / InputRequiredResult 重放与 elicitation → 人工确认只能 pending + 轮询。
- **L2** 单实例假设：乐观锁保证状态一致，但不保证 actualExecutor 恰好执行一次（并发加固需原子 claim）。
- **L3** /confirmations 的凭据未与 AI 客户端凭据物理隔离（同一 API Key 体系）。现有缓解：callerId+input_hash 绑定 + 全量审计；规划：独立确认台凭据（阶段六候选）。
- **L4** vredis 工具 requiredTenant 均为 null（单租户部署），租户隔离机制已实现未启用。
- **L5** rate_limit 字段已入库但未强制执行。
- **L6** 审计未做密码学防篡改（WORM/哈希链）；依赖 DB 权限 + 追加式访问模式。
