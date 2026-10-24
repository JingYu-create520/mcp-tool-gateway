package com.example.mcp.support;

import tools.jackson.databind.json.JsonMapper;

/**
 * 进程内共享的 JsonMapper 实例（线程安全，不可变配置）。
 */
public final class JsonMappers {

    public static final JsonMapper MAPPER = JsonMapper.builder().build();

    private JsonMappers() {
    }
}
