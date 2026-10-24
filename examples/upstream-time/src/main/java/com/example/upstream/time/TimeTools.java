package com.example.upstream.time;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 联邦演示工具：返回服务器当前时间。
 */
@Component
public class TimeTools {

    @McpTool(
        name = "now",
        description = "返回服务器当前时间（ISO-8601）",
        annotations = @McpTool.McpAnnotations(
            title = "now",
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false))
    public String now(@McpToolParam(description = "时区偏移，可选，例如 +08:00", required = false) String offset) {
        Instant instant = Instant.now();
        if (offset == null || offset.isBlank()) {
            return instant.toString();
        }
        // 参数必须真实生效：按偏移换算本地化时间（非法偏移返回结构化错误而不是忽略参数）
        try {
            return instant.atOffset(java.time.ZoneOffset.of(offset))
                    .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (java.time.DateTimeException e) {
            return "{\"error\":\"invalid_offset\",\"message\":\"时区偏移格式应为 ±HH:MM\"}";
        }
    }
}
