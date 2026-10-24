# 权限矩阵（阶段五完整版）

两层模型：**URL 层**（Spring Security，SecurityConfig）+ **工具层**（PermissionChecker 按 tool_registry 行上的 requiredRole/requiredTenant）。工具层拒绝在 MCP 内表现为 `isError=true` + `permission_denied` 结果，在 REST 侧为 403。

## URL 层

| 路径 | 要求 | 匿名 | 角色不足 |
|---|---|---|---|
| /mcp/** | 认证（任意有效 Key） | 401 | — |
| /confirmations/** | confirmer 角色 | 401 | 403 |
| /admin/**（含 /admin/tools、/admin/audit、/admin/api-keys） | admin 角色 | 401 | 403 |
| /actuator/health、/actuator/prometheus、/error | 公开 | 200 | — |
| /confirmation-console.html | 公开（仅 UI 壳；其渲染数据来自 /confirmations/**，仍需 confirmer） | 200 | — |
| /swagger-ui.html、/swagger-ui/**、/v3/api-docs/** | 公开（仅接口元数据） | 200 | — |
| 其余任意路径 | 认证兜底（anyRequest().authenticated()） | 401 | 403 |

## 工具层（全部工具）

| 工具 | 来源模块 | 注册方式 | 副作用 | requiredRole | requiredTenant | 是否需要确认 | 说明 |
|---|---|---|---|---|---|---|---|
| echo | gateway-core | @McpTool 注解 | READ | — | — | 否 | 连通性自检，返回原文 |
| get_confirmation_status | gateway-core | @McpTool 注解 | READ | — | — | 否 | AI 客户端轮询 pending 状态；仅限查**本人**（同 callerId）的确认单 |
| vredis_search | gateway-tools | ExternalToolRegistrar（SPI） | READ | user | tenant-a | 否 | 键模式检索 |
| vredis_stats | gateway-tools | ExternalToolRegistrar（SPI） | READ | user | tenant-a | 否 | 统计信息 |
| vredis_upsert | gateway-tools | ExternalToolRegistrar（SPI） | WRITE | admin | tenant-a | **是**（pending，30 分钟过期） | 写入/更新键值 |
| vredis_delete | gateway-tools | ExternalToolRegistrar（SPI） | DANGEROUS | admin | tenant-a | **是**（pending，30 分钟过期） | 删除键；DANGEROUS 语义 = MCP destructiveHint=true |
| time__now | upstream-time | FederatedToolRegistry（联邦同步） | READ | user | — | 否 | 联邦工具：返回当前时间 |
| weather__forecast | upstream-weather | FederatedToolRegistry（联邦同步） | READ | user | — | 否 | 联邦工具：城市天气（假数据） |

## 规则

- **匹配顺序**：URL 层先行（401/403）→ 工具层 requiredRole/requiredTenant（null 表示不限制）→ 未注册的工具名一律拒绝（MCP 侧 `invalid_request` isError 结果）。管理端注册强制 sideEffect 必填，"未分级"行不存在。
- **确认执行语义**：WRITE/DANGEROUS 首次调用创建 pending；confirm 后**同调用方 + 同输入**（SHA-256 指纹）再次调用才真正执行，执行前原子 claim（CONFIRMED→EXECUTING，多实例下只有抢到的请求执行，确认有效期在数据库层复查），且执行使用**确认时保存的输入**。完整审计轨迹四态：PENDING → CONFIRMED → EXECUTING → EXECUTED（失败为 EXECUTION_FAILED 后退回 CONFIRMED 可重试）。
- **确认台列表端点**：GET /confirmations?status= 支持逗号分隔多状态（默认 PENDING），最近 50 条倒序；非法状态名 400。
- **限流语义**：工具可配置 rateLimit（每 60 秒允许次数，按 tool+caller 计数）；超限返回 `rate_limited` 错误并落 RATE_LIMITED 审计；null/0 = 不限流。
- **幂等**：confirm/reject 重复调用返回当前状态，不报错。
- **内置工具以代码为准**：重启时 ExternalToolRegistrar 会把四个 vredis 工具的定义恢复为代码声明（含被管理端删除的），管理端仍可运行时增删自定义工具。
- **role 体系**：API Key 存数据库（SHA-256 哈希），`POST /admin/api-keys` 发钥（明文仅创建响应返回一次）、`DELETE /admin/api-keys/{callerId}` 禁用；`DbApiKeySeeder` 启动时写开发/测试种子（可关）——`admin-key`（admin+user+confirmer，tenant-a）、`user-key`（user，tenant-a）。确认台仅 confirmer 可访问，user-key 天然被隔离在外。
