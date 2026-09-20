package com.ghy.mutiagent.model;

import com.ghy.mutiagent.model.enums.TravelStage;
import lombok.Data;

import java.util.List;

/**
 * 对话步进结果：编排器每轮返回的阶段 + 回复文案 + 下一问 + 当前偏好 + 候选集。
 */
@Data
public class ChatStepResult {
    private String sessionId;
    private TravelStage stage;
    private String message;
    private Question question;
    private TravelPreference preference;
    private String conflict;
    private List<AttractionCandidate> attractionCandidates;
    private List<FoodCandidate> foodCandidates;
    private List<HotelCandidate> hotelCandidates;
    /** 阶段2：联网检索的美食候选（知识库外，仅参考展示） */
    private List<WebFoodCandidate> webFoodCandidates;
    private List<Long> selectedAttractionIds;
    private List<Long> selectedFoodIds;
    private List<Long> selectedHotelIds;
    /** 行程生成结果（阶段 D） */
    private Long itineraryId;
    /** 会话权威快照版本（S06-B）：客户端后续写操作回传 expectedRevision */
    private Long sessionRevision;
    private String itineraryText;
    private ItineraryPlan plan;
    /** Agent 针对用户特殊要求给出的「推荐方法」建议文本 */
    private String candidateAdvice;
    /**
     * S07：本轮结果状态。NEEDS_CONFIRMATION = 锁定项与新约束冲突（或存在其他需用户裁决的阻断），
     * 未提交任何结果；其余流程为 null。
     */
    private String status;
    /** S07：锁定项冲突码（与 status=NEEDS_CONFIRMATION 同现） */
    private List<String> conflictCodes;
    /** B 方案：疲劳超载行程待用户确认（与阶段 ITINERARY 且无 plan 同现，前端展示确认卡片） */
    private Boolean pendingFatigueConfirm;
    /** B 方案：待确认草稿最满一天的疲劳分（展示文案用） */
    private Double pendingFatigueScore;
    /** 预算超支待确认（已选餐厅组合超预算且无更便宜已选可替换，前端展示知情放行卡片） */
    private Boolean pendingBudgetConfirm;
    /** 预算超支待确认的超支金额（展示文案用） */
    private java.math.BigDecimal pendingBudgetOver;
    /** 行程偏好问卷（PLAN_QUIZ 阶段；为 null 表示本步不涉及问卷） */
    private PlanQuiz planQuiz;
}
