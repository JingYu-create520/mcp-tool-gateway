/**
 * 后端工具封装模块。
 *
 * <p>规划（阶段五落地）：通过 HTTP Adapter 调用 vredis 等后端服务，
 * 网关不直连后端私有协议。每个工具在注册时声明
 * {@link com.example.mcp.security.ToolAction} 分级，未声明分级的工具
 * 按默认拒绝（DENY）处理。
 */
package com.example.mcp.tools;
