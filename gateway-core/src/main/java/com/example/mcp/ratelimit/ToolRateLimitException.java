package com.example.mcp.ratelimit;

/** 工具调用超出限流配额（按 tool + caller 每 60 秒计数）。 */
public class ToolRateLimitException extends RuntimeException {

    public ToolRateLimitException(String toolName, Integer rateLimit) {
        super("工具 '%s' 调用超出限流配额（每 60 秒 %d 次），请稍后重试".formatted(toolName, rateLimit));
    }
}
