package com.ghy.mutiagent.service.eval;

import java.util.List;

/**
 * 加载后的数据集整体：结构字段 + 工具事实快照 + 样本（保持文件顺序）+ 内容哈希 + 加载诊断。
 * schemaErrors 只承载「数据集不可读/结构级」问题；样本契约级错误（重复 ID、缺 expected、
 * 未知 PlaceKey、缺版本）由 DatasetValidator 报出，两类错误分开观察。
 */
public record DatasetFile(String schemaVersion, String datasetId, String version, String split,
                          boolean synthetic, String providerMode, String description,
                          DatasetToolSnapshot toolSnapshot, List<DatasetSample> samples,
                          String datasetHash, List<String> schemaErrors,
                          List<String> fixtureValidationErrors) {

    public DatasetFile {
        samples = samples == null ? List.of() : List.copyOf(samples);
        schemaErrors = schemaErrors == null ? List.of() : List.copyOf(schemaErrors);
        fixtureValidationErrors = fixtureValidationErrors == null
                ? List.of() : List.copyOf(fixtureValidationErrors);
    }
}
