package com.ghy.mutiagent.config;

import java.util.HashMap;
import java.util.Map;

/**
 * 单次线程内的模型路由结果。Agent 调用完成后，业务用量记录可取得实际命中的模型，
 * 避免故障转移后仍把主模型写入审计记录。
 */
public final class LlmRouteContext {

    private static final ThreadLocal<Map<String, String>> SELECTED =
            ThreadLocal.withInitial(HashMap::new);

    private LlmRouteContext() {
    }

    static void record(String route, String model) {
        SELECTED.get().put(route, model);
    }

    public static String consume(String route, String fallback) {
        Map<String, String> selected = SELECTED.get();
        String model = selected.remove(route);
        if (selected.isEmpty()) {
            SELECTED.remove();
        }
        return model == null || model.isBlank() ? fallback : model;
    }

    static void clear() {
        SELECTED.remove();
    }
}
