package com.pkb.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Jackson 封装与宽松 JSON 解析工具。
 */
public final class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonUtil() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            throw new RuntimeException("JSON 解析失败: " + e.getMessage(), e);
        }
    }

    public static <T> T fromJson(String json, TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new RuntimeException("JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从可能包含杂讯的 LLM 输出中提取第一个 JSON 对象（宽松）。
     */
    public static String extractFirstJsonObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        String candidate = text.substring(start, end + 1);
        // 去除非法尾逗号
        candidate = candidate.replaceAll(",\\s*}", "}").replaceAll(",\\s*]", "]");
        return candidate;
    }
}
