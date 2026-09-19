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
        return offset == null || offset.isBlank() ? instant.toString() : instant + " (requested offset " + offset + ")";
    }
}
