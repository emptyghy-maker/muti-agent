package com.ghy.mutiagent.model;

import lombok.Data;

import java.util.Map;

/**
 * PreferenceAgent 的输出：从用户回复中抽取的字段更新 + 冲突描述 + 环节跳过标记。
 * updates：field → 值（字符串，Java 侧做类型转换与校验）；值为 UNSURE 表示用户没想好。
 * skip：仅裸否定词场景允许 noHotel / noAttraction（用户是不需要该环节，而非没想好），其余为 null。
 */
@Data
public class PreferenceResult {
    private Map<String, String> updates;
    private String conflict;
    private String skip;
}
