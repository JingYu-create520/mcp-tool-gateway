package com.example.mcp.tools;

import java.util.List;
import java.util.function.Function;

/**
 * 工具执行器 SPI（方案 B）：后端集成模块（如 gateway-tools 的 vredis）实现本接口，
 * gateway-core 的 ExternalToolRegistrar 在启动时收集所有实现，
 * 完成"工具定义入库 + 执行器绑定"，注册链路统一走 DynamicToolRegistry → SafeToolExecutor。
 *
 * <p>选择 SPI 而非让 gateway-tools 直接依赖 core 的原因：
 * core 依赖 tools（编译期已成立），tools 反向依赖 core 会形成模块环。
 */
public interface ToolExecutorResolver {

    /** 本集成模块提供的全部工具定义。 */
    List<ToolDefinition> definitions();

    /** 按工具名返回真实执行器（HTTP Adapter）；未知工具返回 null。 */
    Function<String, String> executorFor(String toolName);
}
