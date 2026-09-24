package com.ghy.mutiagent.model;

import lombok.Data;

/**
 * 景点候选（LLM 输出 attractionId + feature + why，Java 侧按 id 回填 name 并做白名单校验）。
 */
@Data
public class AttractionCandidate {
    private Long attractionId;
    private String name;
    /** 一句话特色 */
    private String feature;
    private String why;
    /** 综合评分 0~10（AHP 权重 × 路径/成本/旅游需求准则分），降序展示 */
    private Double score;
    /** 结构化标签（受控词表，逗号分隔），前端徽章展示 */
    private String tags;
    /** 标签评分明细（如「+1.2 情侣/夜景 · −0.6 热闹」），前端悬浮说明 */
    private String tagNote;
    /** 距用户指定位置锚点的距离（公里）；未指定或无法核实时为空。 */
    private Double distanceToAnchor;
}
