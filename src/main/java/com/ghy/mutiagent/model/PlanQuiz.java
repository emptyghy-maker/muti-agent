package com.ghy.mutiagent.model;

import lombok.Data;

/**
 * 行程偏好问卷（PLAN_QUIZ 阶段）：由编排器按会话状态判定要问的问题集合。
 * 前端据此渲染对应问题卡片；问卷只答一次（state.planQuizAnswered 持久化）。
 */
@Data
public class PlanQuiz {
    /** 是否已选酒店（决定「回酒店」/「回家」措辞） */
    private boolean hotelSelected;
    /** 已选景点中是否含夜景标签景点（deadline 文案注明夜景会安排在此前） */
    private boolean hasNight;
    /** 是否提问活动安排倾向（未跳过景点阶段才问） */
    private boolean askActivityBias;
    /** 是否提问夜景数量（未跳过景点阶段且已选含夜景才问） */
    private boolean askNightPlan;
    /** 夜景系数最高的已选景点名（「只看 1 个」默认选项的文案） */
    private String topNightName;
}
