package com.ghy.mutiagent.service.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 判据快照（手册 §3.3 判据边界）：期望约束 + 工具事实 + 标注来源 + 评审版本。
 *
 * 不可变：构造与每次导出都深拷贝，样本输入文本没有任何路径写入判据——
 * 恶意提示样本只能作为样本数据进入 provider，不能更改 expected、工具白名单或评审提示词。
 * 输入输出比对不能把模型自身「全部满足」的陈述当标签。
 */
public final class JudgeConfig {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String datasetId;
    private final Map<String, Object> expected;
    private final Map<String, Object> toolSnapshot;
    private final String labelSource;
    private final String judgeVersion;

    public JudgeConfig(String datasetId, Map<String, Object> expected,
                       Map<String, Object> toolSnapshot, String labelSource, String judgeVersion) {
        this.datasetId = datasetId;
        this.expected = deepCopy(expected);
        this.toolSnapshot = deepCopy(toolSnapshot);
        this.labelSource = labelSource;
        this.judgeVersion = judgeVersion;
    }

    public String datasetId() {
        return datasetId;
    }

    public Map<String, Object> expected() {
        return deepCopy(expected);
    }

    public Map<String, Object> toolSnapshot() {
        return deepCopy(toolSnapshot);
    }

    public String labelSource() {
        return labelSource;
    }

    public String judgeVersion() {
        return judgeVersion;
    }

    /** 独立深拷贝快照：执行前/后各取一份用于比对，任何执行路径都改不到原判据 */
    public JudgeConfig snapshot() {
        return new JudgeConfig(datasetId, expected, toolSnapshot, labelSource, judgeVersion);
    }

    /** 判据的 JSON 形态（深拷贝，序列化顺序稳定，供执行前后一致性比对） */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("datasetId", datasetId);
        m.put("expected", deepCopy(expected));
        m.put("toolSnapshot", deepCopy(toolSnapshot));
        m.put("labelSource", labelSource);
        m.put("judgeVersion", judgeVersion);
        return m;
    }

    public static JudgeConfig fromSample(DatasetSample sample, DatasetToolSnapshot toolSnapshot,
                                         String judgeVersion) {
        return new JudgeConfig(
                sample == null ? null : sample.id(),
                sample == null ? Map.of() : sample.expected(),
                toolSnapshot == null ? Map.of() : toolSnapshot.toMap(),
                sample == null ? null : sample.labelSource(),
                judgeVersion);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> src) {
        if (src == null || src.isEmpty()) {
            return Map.of();
        }
        try {
            byte[] bytes = JSON.writeValueAsBytes(src);
            return JSON.readValue(bytes, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            // JSON 安全的判据不应走到这里；防御性回退为浅拷贝并保持不可变接口
            return new LinkedHashMap<>(src);
        }
    }
}
