package com.ghy.mutiagent.model;

import lombok.Data;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 旅行偏好（问询阶段收集的结构化结果）。
 *
 * 每个字段三态（防重复提问的核心）：
 * - fieldStates 里没有该字段  → MISSING（未获取，需要问）
 * - CONFIRMED                 → 用户已明确回答
 * - DEFAULTED                 → 用户答「随便/没想好」，已按默认值处理，不再追问
 */
@Data
public class TravelPreference {

    private Long destinationId;
    private Integer days;
    private BigDecimal totalBudget;
    private Integer peopleCount;
    /** 打卡拍照 / 娱乐项目 / 混合 */
    private String attractionType;
    /** 清淡 / 辣 / 本地特色菜 / … */
    private String foodTaste;
    /** 体力好 / 一般 / 偏弱 */
    private String energyLevel;
    /** 性价比优先 / 体验优先 */
    private String hotelStyle;
    private String specialRequests;
    /** 餐次结构（用户明确提出才设置；null=按默认配置处理） */
    private MealPlan mealPlan;
    /** 行程偏好问卷：每天起床出发时间（HH:mm，如 09:00）；null=默认 09:00 */
    private String wakeTime;
    /** 行程偏好问卷：每晚回酒店/回家截止时间（HH:mm，如 22:00；UNLIMITED=不限） */
    private String returnDeadline;
    /** 行程偏好问卷：活动安排倾向 MORNING=上午型 / BALANCED=均衡 / EVENING=下午晚上型 */
    private String activityBias;
    /** 行程偏好问卷：夜景策略 ONE=只看夜景系数最高 1 个 / ALL=都要；null=按默认 ONE */
    private String nightPlan;
    /** 字段级特殊需求备注（如 hotelStyle→「近+便宜」、foodTaste→「人均50以内」），
     *  与总体 specialRequests 分开存储：最后仍会单独询问总体特殊要求 */
    private Map<String, String> fieldNotes = new LinkedHashMap<>();

    private Map<String, String> fieldStates = new LinkedHashMap<>();

    /**
     * 餐次结构：每天几顿早餐/午饭/晚饭与小吃取舍。
     * 各字段可为 null（未提及）；snacksAllowed=false 表示明确不含小吃/夜宵。
     */
    @Data
    public static class MealPlan {
        private Integer breakfastPerDay;
        private Integer lunchPerDay;
        private Integer dinnerPerDay;
        private Boolean snacksAllowed;
    }

    /** 字段是否还没获取（需要问询） */
    public boolean isMissing(String field) {
        return !fieldStates.containsKey(field);
    }

    public void markConfirmed(String field) {
        fieldStates.put(field, "CONFIRMED");
    }

    public void markDefaulted(String field) {
        fieldStates.put(field, "DEFAULTED");
    }

    /** 撤销字段的已答标记（兜底/中途写入不算正式回答，最终问题仍需询问一次） */
    public void unmark(String field) {
        fieldStates.remove(field);
    }

    /** 旧会话快照反序列化时字段可能为 null：统一惰性初始化 */
    public Map<String, String> fieldNotes() {
        if (fieldNotes == null) {
            fieldNotes = new LinkedHashMap<>();
        }
        return fieldNotes;
    }

    /** 字段级备注按累积语义追加（去重），不覆盖已有内容 */
    public void appendFieldNote(String field, String note) {
        if (field == null || note == null || note.isBlank()) {
            return;
        }
        Map<String, String> notes = fieldNotes();
        String existing = notes.get(field);
        notes.put(field, existing == null || existing.isBlank() || existing.contains(note)
                ? note.trim() : existing + "；" + note.trim());
    }
}
