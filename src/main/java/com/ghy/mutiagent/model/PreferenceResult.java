package com.ghy.mutiagent.model;

import lombok.Data;

import java.util.Map;

/**
 * PreferenceAgent 的输出：从用户回复中抽取的字段更新 + 冲突描述。
 * updates：field → 值（字符串，Java 侧做类型转换与校验）；值为 UNSURE 表示用户没想好。
 */
@Data
public class PreferenceResult {
    private Map<String, String> updates;
    private String conflict;
}
