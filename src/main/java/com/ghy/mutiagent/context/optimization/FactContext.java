package com.ghy.mutiagent.context.optimization;

import java.util.List;

/** 必要事实带来源与状态；未知和过期事实不能伪装成已验证。 */
public record FactContext<T>(
        String key,
        T value,
        String status,
        String source,
        String observedAt,
        String validUntil,
        String version,
        String quality,
        List<String> reasonCodes) {

    public static <T> FactContext<T> known(String key, T value, String source) {
        return new FactContext<>(key, value, "VERIFIED", source, null, null, "v1", "KNOWN", List.of());
    }

    public static <T> FactContext<T> unknown(String key, String reason) {
        return new FactContext<>(key, null, "UNKNOWN", null, null, null, "v1", "UNKNOWN",
                reason == null ? List.of() : List.of(reason));
    }
}
