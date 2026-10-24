/**
 * 审计日志模块。
 *
 * <p>业务事务内写入 outbox_message，由 OutboxRelay 异步投递到 audit_log；
 * ShedLock 保证多实例部署下投递任务不并发执行。
 * 审计记录包含：调用方身份、工具名、参数快照、结果、是否经过人工确认、耗时。
 */
package com.example.mcp.audit;
