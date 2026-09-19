package com.ghy.mutiagent.model;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 美食候选：按风味分组（LLM 输出 cuisine + restaurantId 列表，Java 侧回填店铺信息）。
 */
@Data
public class FoodCandidate {
    private String cuisine;
    private List<FoodItem> restaurants;

    @Data
    public static class FoodItem {
        private Long restaurantId;
        private String name;
        private BigDecimal avgPrice;
        private String signatureDish;
        /** 综合评分 0~10（AHP 权重 × 路径/成本/美食需求准则分），降序展示 */
        private Double score;
        /** 餐次归类（早餐/午餐/晚餐/小吃）；仅当用户提出餐次需求时由规则/AI 标注，否则为 null */
        private String mealType;
        /** 结构化标签（受控词表，逗号分隔），前端徽章展示 */
        private String tags;
        /** 标签评分明细（如「+1.2 情侣/氛围 · −0.6 热闹」），前端悬浮说明 */
        private String tagNote;
    }
}
