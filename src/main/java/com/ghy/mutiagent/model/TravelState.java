package com.ghy.mutiagent.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ghy.mutiagent.model.enums.TravelStage;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次规划会话的完整状态，序列化为 JSON 存 Redis（travel:session:{id}，TTL 2h）。
 */
@Data
public class TravelState {

    private String sessionId;
    /** 调整子任务等派生状态的父会话 ID（审计归并用：用量记录写入父会话维度） */
    private String parentSessionId;
    private Long userId;
    /** 登录用户名（用量记录用，创建会话时写入） */
    private String username;
    private Long destinationId;
    private String destinationName;
    private TravelStage stage;
    private TravelPreference preference = new TravelPreference();
    /** 当前正在询问的字段（无则为空） */
    private String currentField;
    /** 已确认/已默认过的字段（审计用） */
    private Set<String> askedFields = new LinkedHashSet<>();
    /** 偏好冲突描述（LLM 检测到矛盾时填充） */
    private String conflict;

    /** 各级候选集与用户选择 */
    private List<AttractionCandidate> attractionCandidates;
    private List<FoodCandidate> foodCandidates;
    private List<HotelCandidate> hotelCandidates;
    private List<Long> selectedAttractionIds = new ArrayList<>();
    private List<Long> selectedFoodIds = new ArrayList<>();
    private List<Long> selectedHotelIds = new ArrayList<>();

    /**
     * 大备选池 + 翻页游标 + 跨批次已勾选集合：
     * 候选一次生成一大池（规则秒出；仅用户提额外要求时才调 AI），
     * 「换一批」只做分页翻取，不再重复调用 AI；已勾选项跨批次保留。
     */
    private List<AttractionCandidate> attractionPool;
    private int attractionCursor;
    private Set<Long> pickedAttractionIds = new LinkedHashSet<>();
    private List<FoodCandidate> foodPool;
    private int foodCursor;
    private Set<Long> pickedFoodIds = new LinkedHashSet<>();
    private List<HotelCandidate> hotelPool;
    private int hotelCursor;
    private Set<Long> pickedHotelIds = new LinkedHashSet<>();

    /**
     * S07：锁定集合（有序去重，与推荐池分离，池重建不清空）与候选快照（分页游标基准）。
     * pickedXxxIds 为旧格式保留字段（双读单写过渡：新写入走 lockedSelection，读取侧兼容合并）。
     */
    private LockedSelection lockedSelection;
    /** 候选快照：类型名（ATTRACTION/FOOD/HOTEL）→ 不可变快照 */
    private Map<String, CandidateSnapshot> candidateSnapshots = new LinkedHashMap<>();
    /** 约束版本：需求约束每次变化 +1；候选快照与分页游标绑定该版本 */
    private int constraintRevision;

    public LockedSelection lockedSelection() {
        if (lockedSelection == null) {
            lockedSelection = new LockedSelection();
        }
        return lockedSelection;
    }

    /** 旧会话状态反序列化时字段可能为 null：统一惰性初始化 */
    public Map<String, CandidateSnapshot> candidateSnapshots() {
        if (candidateSnapshots == null) {
            candidateSnapshots = new LinkedHashMap<>();
        }
        return candidateSnapshots;
    }

    public int bumpConstraintRevision() {
        constraintRevision = constraintRevision + 1;
        return constraintRevision;
    }

    /** 用户在候选阶段的自由表达（注入候选重筛，Agent 感来源；S02 后为快照渲染摘要） */
    private String extraRequest;
    /** S02 跨轮需求快照：有效约束/撤销记录/预算口径/未解析残余，每轮合并同一份 */
    private RequirementSnapshot requirementSnapshot;
    /** Agent 针对特殊要求给出的「推荐方法」建议文本（展示在前端候选区） */
    private String candidateAdvice;
    /** 阶段2：联网检索的美食候选（知识库外，仅参考展示，未经审核不入知识库、暂不进行程） */
    private List<WebFoodCandidate> webFoodCandidates;
    /** 最近一次触发联网检索的原始消息（防同一句话重复检索；仅内存，不序列化） */
    @JsonIgnore
    private String webSearchKey;
    /** 用户明确不需要美食：跳过美食挑选环节，行程不安排 restaurant 节点 */
    private Boolean noFoodNeeded;
    /** 用户明确不需要酒店：跳过酒店挑选环节，行程不安排 hotel 节点、住宿费用为 0 */
    private Boolean noHotelNeeded;
    /** 用户明确不需要景点：跳过景点挑选环节，行程不安排 attraction 节点 */
    private Boolean noAttractionNeeded;
    /** 行程偏好问卷已作答（PLAN_QUIZ 阶段后置 true，同一会话不再重复提问） */
    private Boolean planQuizAnswered;
    /** 需求关键字（确定性规则 + RequirementAgent 输出合并）：候选匹配加权用 */
    private List<String> needTags;
    /** AHP 评分权重快照（path/cost/sightseeing/food），需求分析后计算，各阶段评分共用 */
    private Map<String, Double> weights;

    /** 行程生成后的 itinerary_id */
    private Long itineraryId;
    /** 会话权威快照版本（S06-B）：随读回填，客户端写入时作为 expectedRevision 回传 */
    private Long sessionRevision;
    /** 生成的行程（结构化） */
    private ItineraryPlan plan;
    /** 行程文本（Java 渲染，含预计消费） */
    private String itineraryText;
    /** B 方案：疲劳超载待用户确认的行程草稿（完整版：已补休息/饭点/账单，确认后发布） */
    private ItineraryPlan pendingPlan;
    /** B 方案：疲劳超载待确认标记（前端据快照展示确认卡片） */
    private Boolean pendingFatigueConfirm;
    /** B 方案：待确认草稿最满一天的疲劳分（展示文案用） */
    private Double pendingFatigueScore;
    /** B 方案：本会话用户确认发布过疲劳超载行程（审计标记，发布后留存） */
    private Boolean fatigueOverrideAccepted;
    /** 预算超支待确认标记（已选餐厅组合超预算且无更便宜已选可替换时进入，前端展示知情放行卡片） */
    private Boolean pendingBudgetConfirm;
    /** 预算超支待确认的超支金额（展示文案用） */
    private BigDecimal pendingBudgetOver;
    /** 用户确认发布过超预算行程（审计标记，发布后留存） */
    private Boolean budgetOverrideAccepted;
    /** ADJUST 调整模式上下文（原行程 JSON + 用户诉求），仅调整流程使用 */
    private String adjustContext;
    private LocalDateTime createdAt;

    // ============ 本轮对话的临时用量归集（仅内存，不序列化进 Redis） ============

    @JsonIgnore
    private int turnInputTokens;
    @JsonIgnore
    private int turnOutputTokens;
    @JsonIgnore
    private final Set<String> turnModels = new LinkedHashSet<>();
    @JsonIgnore
    private String turnChannel;

    /** 新一轮对话开始：清空本轮归集 */
    public void resetTurnUsage() {
        turnInputTokens = 0;
        turnOutputTokens = 0;
        turnModels.clear();
        turnChannel = null;
    }

    /** 归集本轮一次 LLM 调用的用量、模型与渠道（渠道按优先级合并） */
    public void addTurnUsage(String model, int inputTokens, int outputTokens, String channel) {
        turnInputTokens += inputTokens;
        turnOutputTokens += outputTokens;
        if (model != null && !model.isBlank()) {
            turnModels.add(model);
        }
        turnChannel = UsageChannel.merge(turnChannel, channel);
    }
}
