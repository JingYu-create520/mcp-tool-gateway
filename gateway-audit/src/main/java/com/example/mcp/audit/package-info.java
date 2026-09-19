/**
 * 审计日志模块。
 *
 * <p>规划（阶段四落地）：业务事务内写入 Okapi 事务性 Outbox（PostgreSQL 16），
 * 由后台任务投递并落审计表；ShedLock 保证多实例部署下投递任务不并发执行。
 * 审计记录包含：调用方身份、工具名、参数快照、ToolAction 分级、
 * 是否经过人工确认、执行结果与耗时。
 */
package com.example.mcp.audit;
