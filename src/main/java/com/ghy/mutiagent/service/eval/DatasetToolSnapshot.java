package com.ghy.mutiagent.service.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据集声明的固定工具事实快照（手册 §3.1 固定地点/天气事实）：
 * 地点键列表（placeKeys）即执行时的工具白名单，也是 UNKNOWN_PLACE_KEY 校验的基准。
 */
public record DatasetToolSnapshot(String id, String date, String city, String weather,
                                  List<String> placeKeys, List<Map<String, Object>> places) {

    public DatasetToolSnapshot {
        placeKeys = placeKeys == null ? List.of() : List.copyOf(placeKeys);
    }

    public boolean containsKey(String key) {
        return key != null && placeKeys.contains(key);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("date", date);
        m.put("city", city);
        m.put("weather", weather);
        m.put("placeKeys", placeKeys);
        m.put("places", places);
        return m;
    }
}
