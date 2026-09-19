# 权限矩阵（阶段五完整版）

两层模型：**URL 层**（Spring Security，SecurityConfig）+ **工具层**（PermissionChecker 按 tool_registry 行上的 requiredRole/requiredTenant）。工具层拒绝在 MCP 内表现为 `isError=true` + `permission_denied` 结果，在 REST 侧为 403。

## URL 层

| 路径 | 要求 | 匿名 | 角色不足 |
|---|---|---|---|
| /mcp/** | 认证（任意有效 Key） | 401 | — |
| /confirmations/** | 认证（任意有效 Key） | 401 | — |
| /admin/**（含 /admin/tools、/admin/audit） | admin 角色 | 401 | 403 |
| /actuator/health、/error | 公开 | 200 | — |

## 工具层（全部工具）

| 工具 | 来源模块 | 注册方式 | 副作用 | requiredRole | requiredTenant | 是否需要确认 | 说明 |
|---|---|---|---|---|---|---|---|
| echo | gateway-core | @McpTool 注解 | READ | — | — | 否 | 连通性自检，返回原文 |
| get_confirmation_status | gateway-core | @McpTool 注解 | READ | — | — | 否 | AI 客户端轮询 pending 状态 |
| vredis_search | gateway-tools | ExternalToolRegistrar（SPI） | READ | user | null | 否 | 键模式检索 |
| vredis_stats | gateway-tools | ExternalToolRegistrar（SPI） | READ | user | null | 否 | 统计信息 |
| vredis_upsert | gateway-tools | ExternalToolRegistrar（SPI） | WRITE | admin | null | **是**（pending，30 分钟过期） | 写入/更新键值 |
| vredis_delete | gateway-tools | ExternalToolRegistrar（SPI） | DANGEROUS | admin | null | **是**（pending，30 分钟过期） | 删除键；DANGEROUS 语义 = MCP destructiveHint=true |

## 规则

- **匹配顺序**：URL 层先行（401/403）→ 工具层 requiredRole/requiredTenant（null 表示不限制）→ 未知/未分级工具一律 **DENY**。
- **确认执行语义**：WRITE/DANGEROUS 首次调用创建 pending；confirm 后**同调用方 + 同输入**（SHA-256 指纹）再次调用才真正执行，且执行使用**确认时保存的输入**。
- **幂等**：confirm/reject 重复调用返回当前状态，不报错。
- **内置工具以代码为准**：重启时 ExternalToolRegistrar 会把四个 vredis 工具的定义恢复为代码声明（含被管理端删除的），管理端仍可运行时增删自定义工具。
- **role 体系**：开发/测试 Key——`admin-key`（admin+user，tenant-a）、`user-key`（user，tenant-a）；生产必须改为数据库存储 + 密钥哈希。
