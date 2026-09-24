package com.ghy.mutiagent.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 酒店候选（LLM 输出 hotelId + why，Java 侧回填名称/金额/评分/距离）。
 */
@Data
public class HotelCandidate {
    private Long hotelId;
    private String name;
    private BigDecimal pricePerNight;
    private Double rating;
    /** 距已选景点/美食中心的距离（公里，Java 计算） */
    private Double distanceToCenter;
    /** 酒店特色标签（湖景/亲子设施/老牌…） */
    private String feature;
    private String why;
    /** 综合评分 0~10（AHP 权重 × 路径/成本/旅游需求准则分），降序展示 */
    private Double score;
    /** 结构化标签（受控词表，逗号分隔），前端徽章展示 */
    private String tags;
    /** 标签评分明细（如「+1.2 情侣/氛围 · −0.6 热闹」），前端悬浮说明 */
    private String tagNote;
    /** 距用户指定位置锚点的距离（公里）；未指定或无法核实时为空。 */
    private Double distanceToAnchor;
}
