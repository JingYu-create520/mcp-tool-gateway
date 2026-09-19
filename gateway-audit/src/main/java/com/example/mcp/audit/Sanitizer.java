package com.example.mcp.audit;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 审计字段脱敏：password / token / apiKey / secret / authorization 等敏感字段值替换为 ***。
 * 优先 JSON 解析后递归替换（大小写与下划线/连写变体都识别）；
 * 解析失败（非 JSON/坏 JSON）降级为正则替换。
 */
public final class Sanitizer {

    public static final String MASK = "***";

    private static final Set<String> SENSITIVE_KEYS =
            Set.of("password", "token", "apikey", "secret", "authorization");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(\"(?i:password|token|api[_-]?key|secret|authorization)\"\\s*:\\s*\")[^\"]*\"");

    private Sanitizer() {
    }

    public static String sanitize(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            Map<String, Object> root = JSON.readValue(json, MAP_TYPE);
            maskInMap(root);
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            return SENSITIVE_FIELD.matcher(json).replaceAll("$1" + MASK + "\"");
        }
    }

    private static boolean isSensitive(String key) {
        String normalized = key.toLowerCase().replace("_", "").replace("-", "");
        return SENSITIVE_KEYS.contains(normalized);
    }

    @SuppressWarnings("unchecked")
    private static void maskInMap(Map<String, Object> map) {
        map.replaceAll((key, value) -> isSensitive(key) ? MASK : value);
        for (Object value : new ArrayList<>(map.values())) {
            if (value instanceof Map<?, ?> nested) {
                maskInMap((Map<String, Object>) nested);
            } else if (value instanceof List<?> list) {
                maskInList((List<Object>) list);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void maskInList(List<Object> list) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) instanceof Map<?, ?> nested) {
                maskInMap((Map<String, Object>) nested);
            } else if (list.get(i) instanceof List<?> nestedList) {
                maskInList((List<Object>) nestedList);
            }
        }
    }
}
