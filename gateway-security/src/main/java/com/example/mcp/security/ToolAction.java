package com.example.mcp.security;

/**
 * 工具副作用分级，是权限策略（阶段二）、人工确认（阶段三）与审计（阶段四）的统一依据。
 */
public enum ToolAction {

    /** 只读操作，鉴权通过后直通执行。 */
    READ,

    /** 有副作用，执行前必须进入人工确认（pending + 轮询）。 */
    WRITE,

    /** 高危副作用（批量删除、清库等），默认拒绝，显式放行后仍需人工确认。 */
    DANGEROUS;

    /** READ 直通；WRITE / DANGEROUS 必须经过人工确认状态机。 */
    public boolean requiresConfirmation() {
        return this != READ;
    }
}
