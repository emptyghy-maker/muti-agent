package com.ghy.mutiagent.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 不可变候选快照（S07）：一次约束版本下、召回+硬过滤+排序完成后的有序候选键集合。
 * 分页游标绑定 snapshotId + constraintRevision + offset；约束变化后旧快照游标返回 REVISION_CONFLICT。
 *
 * orderedKeys 使用类型化 PlaceKey 字符串（如 ATTRACTION:1），与 FOOD:1 严格区分。
 * evidence 记录召回统计（scannedCount/eligibleCount/shortage/reasonCodes/各来源命中），
 * 供短缺说明与验收取证，不得伪造「全城没有合适地点」的结论。
 */
@Data
public class CandidateSnapshot {

    private String snapshotId;
    /** 生成该快照时的约束版本 */
    private int constraintRevision;
    /** 有序候选键（类型化 PlaceKey 字符串） */
    private List<String> orderedKeys = new ArrayList<>();
    /** 召回统计与各来源命中（SQL/AI/BACKFILL 均经统一硬过滤出口） */
    private Map<String, Object> evidence = new LinkedHashMap<>();
    private LocalDateTime createdAt;
}
