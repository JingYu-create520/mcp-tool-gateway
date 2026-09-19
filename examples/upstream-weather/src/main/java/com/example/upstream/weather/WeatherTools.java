package com.example.upstream.weather;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * 联邦演示工具：城市天气预报（假数据）。
 */
@Component
public class WeatherTools {

    @McpTool(
        name = "forecast",
        description = "查询指定城市的明日天气预报（演示用假数据）",
        annotations = @McpTool.McpAnnotations(
            title = "forecast",
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false))
    public String forecast(@McpToolParam(description = "城市名，例如 Shanghai") String city) {
        return "明日 " + city + "：晴，24~28°C，微风（模拟数据）";
    }
}
