package com.ghy.mutiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.ghy.mutiagent.agent.AttractionAgent;
import com.ghy.mutiagent.agent.FoodAgent;
import com.ghy.mutiagent.agent.HotelAgent;
import com.ghy.mutiagent.common.GeoUtils;
import com.ghy.mutiagent.common.JsonUtils;
import com.ghy.mutiagent.config.LlmRouteContext;
import com.ghy.mutiagent.config.CandidateFoodConfig;
import com.ghy.mutiagent.context.baseline.ContextBaselineRecorder;
import com.ghy.mutiagent.context.baseline.ContextCaptureRequest;
import com.ghy.mutiagent.context.baseline.ContextObservation;
import com.ghy.mutiagent.context.baseline.ContextSectionNames;
import com.ghy.mutiagent.context.agent.AgentContextInvocation;
import com.ghy.mutiagent.context.agent.AgentContextRole;
import com.ghy.mutiagent.context.agent.AgentContextRuntime;
import com.ghy.mutiagent.model.AttractionCandidate;
import com.ghy.mutiagent.model.CandidateSnapshot;
import com.ghy.mutiagent.model.ConstraintEntry;
import com.ghy.mutiagent.model.FoodCandidate;
import com.ghy.mutiagent.model.HotelCandidate;
import com.ghy.mutiagent.model.LockedSelection;
import com.ghy.mutiagent.model.LocationConstraint;
import com.ghy.mutiagent.model.RequirementSnapshot;
import com.ghy.mutiagent.model.ResolvedPlanningPolicy;
import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.model.UsageChannel;
import com.ghy.mutiagent.model.WebAttractionCandidate;
import com.ghy.mutiagent.model.WebFoodCandidate;
import com.ghy.mutiagent.model.WebHotelCandidate;
import com.ghy.mutiagent.repository.entity.Attraction;
import com.ghy.mutiagent.repository.entity.Hotel;
import com.ghy.mutiagent.repository.entity.Restaurant;
import com.ghy.mutiagent.repository.entity.WebFoodAudit;
import com.ghy.mutiagent.repository.mapper.AttractionMapper;
import com.ghy.mutiagent.repository.mapper.HotelMapper;
import com.ghy.mutiagent.repository.mapper.RestaurantMapper;
import com.ghy.mutiagent.repository.mapper.WebFoodAuditMapper;
import com.ghy.mutiagent.rule.AhpWeightCalculator;
import com.ghy.mutiagent.rule.CandidateScorer;
import com.ghy.mutiagent.rule.HardConstraintEvaluator;
import com.ghy.mutiagent.rule.LocationConstraintSupport;
import com.ghy.mutiagent.rule.MealPolicySupport;
import com.ghy.mutiagent.rule.PlanningPolicyResolver;
import com.ghy.mutiagent.rule.TagMatcher;
import com.ghy.mutiagent.model.requirement.RequirementScope;
import com.ghy.mutiagent.trace.AgentTrace;
import com.ghy.mutiagent.trace.TraceContext;
import com.ghy.mutiagent.trace.TraceService;
import com.ghy.mutiagent.service.validation.WebAttractionValidator;
import com.ghy.mutiagent.service.validation.WebFoodValidator;
import com.ghy.mutiagent.service.validation.WebHotelValidator;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 候选生成服务（三级候选：景点 → 美食 → 酒店）。
 *
 * 设计（按体验反馈调整）：
 * 1. 默认走知识库规则：一次生成「大备选池」（秒出、无 AI 调用）；
 * 2. 「换一批」只是从备选池翻页（不重复、不调 AI，已勾选跨批次保留置顶）；
 * 3. 用户打字提出额外要求时，才调用对应 Agent 带着要求重筛备选池；
 * 4. AI 输出始终白名单校验 + 完整信息回填（防幻觉是硬要求）；
 * 5. AI 调用/解析失败 → 沿用规则池并打完整日志（含原始输出片段）便于定位。
 */
@Service
public class CandidateService {

    private static final Logger log = LoggerFactory.getLogger(CandidateService.class);
    private static final Pattern VERIFIED_ADVICE_SUFFIX = Pattern.compile(
            "(已(?:联网检索扩充|复用历史网搜结果补充)\\s*\\d+\\s*[^。]+。|"
                    + "（(?:本次未获取到新的联网结果|联网检索暂不可用)[^）]*）)");

    /** 备选池与每批展示数量 */
    private static final int ATTRACTION_POOL_MAX = 16;
    private static final int ATTRACTION_BATCH = 8;
    private static final int FOOD_POOL_MAX = 24;     // 总店铺数（目标数最多 9×天数，放宽上限）
    private static final int FOOD_PER_GROUP = 6;     // 每组最多店铺数
    private static final int FOOD_BATCH = 8;         // 每批店铺数
    private static final int HOTEL_POOL_MAX = 10;
    private static final int HOTEL_BATCH = 5;
    /** AI 重筛后酒店池低于该数量时用规则池补足（AI 精挑常只有 3 家，太少用户没法选） */
    private static final int HOTEL_POOL_FLOOR = 6;
    /** AI 重筛后景点池低于该数量时触发联网检索/规则补足（对称于酒店 HOTEL_POOL_FLOOR） */
    private static final int ATTRACTION_SEARCH_FLOOR = 8;
    /** AI 重筛时最多保留数量 */
    private static final int LLM_SELECT_MAX = 12;
    /** 联网检索单次最多接收条数（提示词上限 20，留余量） */
    private static final int WEB_FOOD_MAX = 24;
    /** 景点/酒店联网检索单次最多接收条数（与美食同口径） */
    private static final int WEB_ATTRACTION_MAX = 24;
    private static final int WEB_HOTEL_MAX = 24;
    /** 分页游标失效（快照被新约束版本取代） */
    public static final String REVISION_CONFLICT = "REVISION_CONFLICT";

    // ==================== S07：统一硬约束出口 / 快照 / 锁定 ====================

    /** 类型化地点键：ATTRACTION:1 与 FOOD:1 严格区分 */
    public static String placeKey(String type, long id) {
        return type + ":" + id;
    }

    /**
     * 通道独立请求解析：通道有专属快照时用快照（三通道并行对话，单通道重筛 latest-wins，
     * 互不污染），无快照的通道沿用全局额外要求（旧行为）。
     */
    private static String channelRequest(TravelState state, String channel) {
        String own = state.channelRequestOf(channel);
        return own != null && !own.isBlank() ? own : state.getExtraRequest();
    }

    /** 分页结果：status=OK 或 REVISION_CONFLICT；pageKeys 为快照 orderedKeys 的纯切片 */
    public record SnapshotPage(String status, List<String> pageKeys, long nextOffset, int wrapped) {
    }

    /** 召回结果：orderedKeys 为通过统一硬过滤的有序候选键；evidence 含 scannedCount/eligibleCount/shortage/reasonCodes */
    public record CandidateRecallResult(List<String> orderedKeys, Map<String, Object> evidence) {
    }

    /**
     * 带上限的分批召回（S07）：全量扫描 + 统一硬过滤，先过滤再截断；
     * 合格数量不足 target 时如实报告 shortage 与 reasonCodes（未知事实不算合格，不凑数）。
     */
    public CandidateRecallResult recallAttractions(TravelState state, int target) {
        List<Attraction> all = attractionMapper.selectList(new LambdaQueryWrapper<Attraction>()
                .eq(Attraction::getDestinationId, state.getDestinationId())
                .eq(Attraction::getStatus, 1));
        HardConstraintEvaluator.HardPolicy policy = buildHardPolicy(state);
        String city = state.getDestinationName();
        List<String> keys = new ArrayList<>();
        int scanned = 0;
        int unknown = 0;
        int locationRejected = 0;
        for (Attraction a : orderAttractions(all, state.getPreference())) {
            scanned++;
            if (!locationEligible(state, LocationConstraintSupport.ATTRACTION,
                    a.getName(), a.getAddress(), a.getLng(), a.getLat())) {
                locationRejected++;
                continue;
            }
            HardConstraintEvaluator.Verdict v = HardConstraintEvaluator.evaluate(attractionFact(a, city), policy);
            if (v.status() == HardConstraintEvaluator.Status.ELIGIBLE) {
                keys.add(placeKey("ATTRACTION", a.getId()));
                if (keys.size() >= target) {
                    break;
                }
            } else if (v.status() == HardConstraintEvaluator.Status.UNKNOWN) {
                unknown++;
            }
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("scannedCount", scanned);
        evidence.put("eligibleCount", keys.size());
        evidence.put("unknownCount", unknown);
        evidence.put("locationRejectedCount", locationRejected);
        int shortage = Math.max(0, target - keys.size());
        evidence.put("shortage", shortage);
        List<String> reasonCodes = new ArrayList<>();
        if (shortage > 0) {
            reasonCodes.add("INSUFFICIENT_ELIGIBLE_CANDIDATES");
        }
        evidence.put("reasonCodes", reasonCodes);
        evidence.put("acceptedByOrigin", Map.of("SQL", new ArrayList<>(keys)));
        newSnapshot(state, "ATTRACTION", keys, evidence);
        return new CandidateRecallResult(new ArrayList<>(keys), evidence);
    }

    /**
     * 快照游标翻页：offset 基准绑定 snapshotId + constraintRevision，
     * 勾选操作不改变翻页基准；约束版本变化后旧游标返回 REVISION_CONFLICT。
     */
    public SnapshotPage pageSnapshot(TravelState state, String type, String snapshotId,
                                     long offset, int pageSize) {
        CandidateSnapshot snap = state.candidateSnapshots().get(type);
        if (snap == null || !snap.getSnapshotId().equals(snapshotId)
                || snap.getConstraintRevision() != state.getConstraintRevision()) {
            return new SnapshotPage(REVISION_CONFLICT, List.of(), offset, 0);
        }
        List<String> keys = snap.getOrderedKeys();
        int from = (int) Math.min(offset, keys.size());
        int to = (int) Math.min(offset + pageSize, keys.size());
        List<String> page = new ArrayList<>(keys.subList(from, to));
        long next = to >= keys.size() ? 0 : to;
        int wrapped = to >= keys.size() ? 1 : 0;
        return new SnapshotPage("OK", page, next, wrapped);
    }

    /** 由已确认需求约束构建硬条件策略：未知事实不启用条件，事实缺失按三态处理 */
    public HardConstraintEvaluator.HardPolicy buildHardPolicy(TravelState state) {
        Set<String> forbidTags = new LinkedHashSet<>();
        BigDecimal maxPrice = null;
        boolean requireAccessible = false;
        RequirementSnapshot snap = state.getRequirementSnapshot();
        if (snap != null && snap.getConstraints() != null) {
            for (ConstraintEntry c : snap.getConstraints()) {
                if (c == null || !"ACTIVE".equals(c.getStatus()) || c.getKey() == null) {
                    continue;
                }
                switch (c.getKey()) {
                    case "avoidClimbing" -> forbidTags.add("爬山");
                    case "avoidHighIntensity" -> forbidTags.add("高强度");
                    case "requireAccessible" -> requireAccessible = true;
                    case "maxItemPrice" -> {
                        try {
                            maxPrice = new BigDecimal(c.getValue());
                        } catch (Exception ignored) {
                            // 非法价格口径：不启用该条件，避免误杀
                        }
                    }
                    default -> { }
                }
            }
        }
        return HardConstraintEvaluator.HardPolicy.of(state.getDestinationName(), forbidTags, maxPrice,
                requireAccessible);
    }

    /**
     * 锁定项与新约束冲突检测：保留用户选择记录，冲突写入 lockedSelection.conflictCodes
     * （LOCKED_CONSTRAINT_CONFLICT），由编排层阻止直接提交，交用户解锁或调整需求。
     */
    public void evaluateLockedConflicts(TravelState state) {
        LockedSelection locked = state.lockedSelection();
        locked.getConflictCodes().clear();
        if (locked.getOrderedKeys().isEmpty()) {
            return;
        }
        HardConstraintEvaluator.HardPolicy policy = buildHardPolicy(state);
        String city = state.getDestinationName();
        for (String key : locked.getOrderedKeys()) {
            if (!locationEligibleKey(state, key)) {
                locked.getConflictCodes().add(LockedSelection.CONFLICT_LOCKED_CONSTRAINT);
                log.warn("[Candidate] 锁定项 {} 超出位置约束范围，标记冲突待用户处理", key);
                continue;
            }
            HardConstraintEvaluator.HardFact fact = factOfKey(key, city);
            if (fact == null) {
                // 锁定项已下架（查无此实体）：同样标记冲突，阻止静默提交
                locked.getConflictCodes().add(LockedSelection.CONFLICT_LOCKED_CONSTRAINT);
                log.warn("[Candidate] 锁定项 {} 已下架或不存在，标记冲突待用户处理", key);
                continue;
            }
            HardConstraintEvaluator.Verdict v = HardConstraintEvaluator.evaluate(fact, policy);
            if (v.status() != HardConstraintEvaluator.Status.ELIGIBLE) {
                locked.getConflictCodes().add(LockedSelection.CONFLICT_LOCKED_CONSTRAINT);
                log.warn("[Candidate] 锁定项 {} 与新约束冲突（{}），阻止直接提交", key, v.reasonCode());
            }
        }
    }

    private boolean locationEligibleKey(TravelState state, String key) {
        int sep = key == null ? -1 : key.indexOf(':');
        if (sep <= 0 || sep >= key.length() - 1) {
            return true;
        }
        try {
            long id = Long.parseLong(key.substring(sep + 1));
            return switch (key.substring(0, sep)) {
                case "ATTRACTION" -> {
                    Attraction a = attractionMapper.selectById(id);
                    yield a == null || locationEligible(state, LocationConstraintSupport.ATTRACTION,
                            a.getName(), a.getAddress(), a.getLng(), a.getLat());
                }
                case "FOOD" -> {
                    Restaurant r = restaurantMapper.selectById(id);
                    yield r == null || locationEligible(state, LocationConstraintSupport.FOOD,
                            r.getName(), r.getAddress(), r.getLng(), r.getLat());
                }
                case "HOTEL" -> {
                    Hotel h = hotelMapper.selectById(id);
                    yield h == null || locationEligible(state, LocationConstraintSupport.HOTEL,
                            h.getName(), h.getAddress(), h.getLng(), h.getLat());
                }
                default -> true;
            };
        } catch (NumberFormatException ignored) {
            return true;
        }
    }

    private HardConstraintEvaluator.HardFact factOfKey(String key, String city) {
        int sep = key.indexOf(':');
        if (sep <= 0 || sep >= key.length() - 1) {
            return null;
        }
        String type = key.substring(0, sep);
        long id;
        try {
            id = Long.parseLong(key.substring(sep + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        return switch (type) {
            case "ATTRACTION" -> {
                Attraction a = attractionMapper.selectById(id);
                yield a == null ? null : attractionFact(a, city);
            }
            case "FOOD" -> {
                Restaurant r = restaurantMapper.selectById(id);
                yield r == null ? null : restaurantFact(r, city);
            }
            case "HOTEL" -> {
                Hotel h = hotelMapper.selectById(id);
                yield h == null ? null : hotelFact(h, city);
            }
            default -> null;
        };
    }

    private static List<String> tagsOf(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return List.of(text.split("[、，,;；/\\s]+"));
    }

    /** 无障碍事实来自数据库结构化字段（features 标记），缺失/未标记一律 UNKNOWN，不伪造 false */
    private static Boolean accessibleOf(String text) {
        return text != null && text.contains("无障碍") ? Boolean.TRUE : null;
    }

    private static HardConstraintEvaluator.HardFact attractionFact(Attraction a, String city) {
        List<String> tags = new ArrayList<>(tagsOf(a.getFeatures()));
        if (a.getCategory() != null && !a.getCategory().isBlank()) {
            tags.add(a.getCategory());
        }
        return HardConstraintEvaluator.HardFact.of(city, tags, a.getTicketPrice(), accessibleOf(a.getFeatures()));
    }

    private static HardConstraintEvaluator.HardFact restaurantFact(Restaurant r, String city) {
        List<String> tags = new ArrayList<>(tagsOf(r.getCuisine()));
        tags.addAll(tagsOf(r.getSignatureDish()));
        return HardConstraintEvaluator.HardFact.of(city, tags, r.getAvgPrice(),
                accessibleOf(r.getBusinessHours()));
    }

    private static HardConstraintEvaluator.HardFact hotelFact(Hotel h, String city) {
        List<String> tags = new ArrayList<>(tagsOf(h.getFeatures()));
        if (h.getLevel() != null && !h.getLevel().isBlank()) {
            tags.add(h.getLevel());
        }
        return HardConstraintEvaluator.HardFact.of(city, tags, h.getPricePerNight(), accessibleOf(h.getFeatures()));
    }

    private static CandidateSnapshot newSnapshot(TravelState state, String type, List<String> keys,
                                                 Map<String, Object> evidence) {
        CandidateSnapshot snap = new CandidateSnapshot();
        snap.setSnapshotId(UUID.randomUUID().toString());
        snap.setConstraintRevision(state.getConstraintRevision());
        snap.setOrderedKeys(new ArrayList<>(keys));
        snap.setEvidence(new LinkedHashMap<>(evidence));
        snap.setCreatedAt(LocalDateTime.now());
        state.candidateSnapshots().put(type, snap);
        return snap;
    }

    private final AttractionMapper attractionMapper;
    private final RestaurantMapper restaurantMapper;
    private final HotelMapper hotelMapper;
    private final AttractionAgent attractionAgent;
    private final FoodAgent foodAgent;
    private final HotelAgent hotelAgent;
    private final TraceService traceService;
    private final UsageService usageService;
    private final ObjectMapper objectMapper;
    private final CandidateFoodConfig foodConfig;

    /** Observability 薄埋点（可空：手动装配的测试进程为 null；模块关闭时内部 noop） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ghy.mutiagent.observability.collection.ObsInstrumentation obsInstrumentation;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ContextBaselineRecorder contextBaselineRecorder = ContextBaselineRecorder.noop();
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AgentContextRuntime agentContextRuntime;

    /** 阶段2 联网检索客户端（可空：未装配/关闭时功能降级为跳过，不阻塞主流程） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DashScopeSearchClient dashScopeSearchClient;

    /** 网搜入库审计 Mapper（可空：手动装配的测试进程为 null 时跳过审计写入） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WebFoodAuditMapper webFoodAuditMapper;

    /** POI 推荐计数与晋升组件（可空：未装配时保持既有行为——无跨会话复用/计数/晋升，功能隔离） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FoodPromotionService promotion;

    /** 联网搜索模型（需支持 enable_search） */
    @Value("${llm.search-model:qwen3.8-max}")
    private String searchModel;

    /** 候选类 Agent 绑定 defaultChatModel，记录用量时注明模型 */
    @Value("${llm.models.default}")
    private String defaultModel;

    public CandidateService(AttractionMapper attractionMapper,
                            RestaurantMapper restaurantMapper,
                            HotelMapper hotelMapper,
                            AttractionAgent attractionAgent,
                            FoodAgent foodAgent,
                            HotelAgent hotelAgent,
                            TraceService traceService,
                            UsageService usageService,
                            ObjectMapper objectMapper,
                            CandidateFoodConfig foodConfig) {
        this.attractionMapper = attractionMapper;
        this.restaurantMapper = restaurantMapper;
        this.hotelMapper = hotelMapper;
        this.attractionAgent = attractionAgent;
        this.foodAgent = foodAgent;
        this.hotelAgent = hotelAgent;
        this.traceService = traceService;
        this.usageService = usageService;
        this.objectMapper = objectMapper;
        this.foodConfig = foodConfig;
    }

    /** S12 调用结果：原始内容 + 本次调用的 span/ctx（解析与验证分层状态由调用方回填） */
    private record Traced<T>(T content, TraceContext ctx, AgentTrace span) {
    }

    /**
     * Agent 调用统一 trace 包装：成功记耗时+token+模型+原始输出，异常记失败并继续抛出（由外层兜底）；
     * 同时落用量记录并归集到本轮。S12：provider 结果与解析/验证结果分层——此处只记录 providerStatus，
     * parseStatus/validationStatus/fallbackReason 由调用方在各自步骤回填。
     */
    private <T> Traced<T> withTrace(TravelState state, String agentName, String action,
                                    ContextObservation observation, Supplier<Result<T>> call) {
        TraceContext ctx = traceService.newTrace(state.getSessionId(), agentName);
        // S12：候选调用也进入 span 注册表（分层状态可被观测；未装配注册表时为空操作）
        ctx.setSpanId(agentName + "-" + UUID.randomUUID());
        ctx.setKind("PROVIDER");
        traceService.register(ctx);
        obsNodeStarted(null, state, "refine-" + agentName, Map.of("action", action));
        long t0 = System.nanoTime();
        try {
            Result<T> r = call.get();
            String actualModel = LlmRouteContext.consume("fast", defaultModel);
            long cost = (System.nanoTime() - t0) / 1_000_000;
            AgentTrace span = AgentTrace.success(agentName, cost, r.tokenUsage());
            contextBaselineRecorder.providerFinished(observation, r.tokenUsage(), cost, "SUCCESS");
            span.setAnswer(UsageService.clip(String.valueOf(r.content()), 2000));
            ctx.add(span);
            ctx.finish("SUCCESS");
            obsNodeEnded(null, state, "refine-" + agentName, "SUCCESS", Map.of(
                    "durationMs", cost, "providerStatus", "SUCCESS"));
            int[] tk = tokenCounts(r.tokenUsage());
            usageService.recordAgent(state.getSessionId(), state.getUserId(), state.getUsername(),
                    state.getStage().name(), action, agentName, r.tokenUsage(), cost, "SUCCESS", null,
                    actualModel, UsageChannel.AGENT, UsageService.clip(String.valueOf(r.content()), 2000));
            state.addTurnUsage(actualModel, tk[0], tk[1], UsageChannel.AGENT);
            return new Traced<>(r.content(), ctx, span);
        } catch (Exception e) {
            String actualModel = LlmRouteContext.consume("fast", defaultModel);
            long cost = (System.nanoTime() - t0) / 1_000_000;
            AgentTrace span = AgentTrace.failure(agentName, cost, e.getMessage());
            contextBaselineRecorder.providerFinished(observation, null, cost, "FAILED");
            ctx.add(span);
            ctx.finish("FAILED");
            obsNodeEnded(null, state, "refine-" + agentName, "FAILED", Map.of(
                    "durationMs", cost, "providerStatus", "FAILED"));
            usageService.recordAgent(state.getSessionId(), state.getUserId(), state.getUsername(),
                    state.getStage().name(), action, agentName, null, cost, "FAILED",
                    e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()),
                    actualModel, UsageChannel.RULE_FALLBACK, null);
            state.addTurnUsage(null, 0, 0, UsageChannel.RULE_FALLBACK);
            throw e;
        } finally {
            traceService.finish(ctx);
        }
    }

    private static int[] tokenCounts(TokenUsage u) {
        return u == null ? new int[]{0, 0}
                : new int[]{u.inputTokenCount() == null ? 0 : u.inputTokenCount(),
                            u.outputTokenCount() == null ? 0 : u.outputTokenCount()};
    }

    // ==================== Observability 薄埋点（可空，无副作用） ====================

    private void obsNodeStarted(String operationId, TravelState state, String nodeExec,
                                Map<String, Object> summary) {
        if (obsInstrumentation != null) {
            obsInstrumentation.nodeStarted(operationId, state.getSessionId(), state.getUserId(),
                    nodeExec, "candidate-refine", summary);
        }
    }

    private void obsNodeEnded(String operationId, TravelState state, String nodeExec,
                              String outcome, Map<String, Object> summary) {
        if (obsInstrumentation != null) {
            obsInstrumentation.nodeEnded(operationId, state.getSessionId(), state.getUserId(),
                    nodeExec, "candidate-refine", outcome, summary);
        }
    }

    private void obsRecall(String operationId, TravelState state, String candidateType,
                           Map<String, Object> evidence) {
        if (obsInstrumentation != null) {
            obsInstrumentation.nodeEnded(operationId, state.getSessionId(), state.getUserId(),
                    "recall-" + candidateType, "recall", "RECALLED", evidence);
        }
    }

    /** 偏好 JSON + 该通道的额外要求（通道独立请求优先，AI 重筛注入用） */
    private String prefJson(TravelState state, String request) throws Exception {
        String json = objectMapper.writeValueAsString(state.getPreference());
        StringBuilder extra = new StringBuilder();
        if (request != null && !request.isBlank()) {
            extra.append("\n\n用户额外要求：").append(request);
        }
        if (state.getNeedTags() != null && !state.getNeedTags().isEmpty()) {
            extra.append("\n\n需求匹配关键字：").append(String.join("、", state.getNeedTags()))
                    .append("\n请优先推荐同时命中多个关键字的候选。");
        }
        LocationConstraint location = state.getLocationConstraint();
        if (location != null) {
            extra.append("\n\n位置约束：").append(LocationConstraintSupport.summary(location))
                    .append("；适用通道=").append(location.getScopes());
            if (LocationConstraint.RESOLVED.equals(location.getStatus())) {
                extra.append("。候选已由 Java 按距离过滤，distanceToAnchorKm 是可验证距离。");
            } else {
                extra.append("。锚点尚未解析，禁止宣称距离要求已满足。");
            }
        }
        ResolvedPlanningPolicy planning = PlanningPolicyResolver.resolve(state);
        if (isExplicitMeal(planning.getMeal().getLunch()) || isExplicitMeal(planning.getMeal().getDinner())
                || planning.getMeal().isExplicitMealComposition()
                || planning.getMeal().getLunchCandidateCount() != null
                || planning.getMeal().getDinnerCandidateCount() != null
                || !planning.getMeal().isSnacksAllowed()) {
            List<String> parts = new ArrayList<>();
            parts.add(mealRuleSummary(planning.getMeal().getLunch(), "午餐"));
            parts.add(mealRuleSummary(planning.getMeal().getDinner(), "晚餐"));
            if (planning.getMeal().isExplicitMealComposition()) {
                parts.add(mealRuleSummary(planning.getMeal().getSnack(), "小吃"));
                parts.add(mealRuleSummary(planning.getMeal().getMainMeal(), "正餐"));
            }
            if (planning.getMeal().getLunchCandidateCount() != null) {
                parts.add("午餐候选店 " + planning.getMeal().getLunchCandidateCount() + " 家");
            }
            if (planning.getMeal().getDinnerCandidateCount() != null) {
                parts.add("晚餐候选店 " + planning.getMeal().getDinnerCandidateCount() + " 家");
            }
            if (planning.getMeal().isSnacksAllowed()) {
                parts.add("含小吃/夜宵");
            } else {
                parts.add("不含小吃/夜宵");
            }
            extra.append("\n\n餐次结构：").append(String.join("，", parts));
        }
        return json + extra;
    }

    private static String mealRuleSummary(ResolvedPlanningPolicy.MealRule rule, String label) {
        if (rule == null) {
            return label + " 0 顿";
        }
        String scope = rule.getScope() == RequirementScope.PER_DAY ? "每天" : "全程";
        return label + " " + scope + " " + rule.getCount() + " 顿（" + rule.getOperator() + "）";
    }

    private String snippet(String raw) {
        if (raw == null) {
            return "<无输出>";
        }
        return raw.length() <= 300 ? raw : raw.substring(0, 300) + "…";
    }

    /** 评分权重：需求分析后写入 state；无则默认优先级（路径优>成本低>旅游需求>美食需求） */
    private Map<String, Double> weightsOf(TravelState state) {
        return state.getWeights() == null ? AhpWeightCalculator.defaultWeights() : state.getWeights();
    }

    /** 已选景点的几何中心（未选景点或所选景点均无坐标时为 null，路径分取中性值） */
    private double[] selectedAttractionCentroid(TravelState state) {
        List<double[]> pts = new ArrayList<>();
        if (state.getSelectedAttractionIds() != null && !state.getSelectedAttractionIds().isEmpty()) {
            attractionMapper.selectBatchIds(state.getSelectedAttractionIds())
                    .forEach(a -> {
                        // 联网扩充景点只有名称和地址时允许暂缺坐标。此类景点仍可参与候选与
                        // 地点文本约束，但不能参与几何中心计算，否则 Double 拆箱会触发 NPE。
                        if (a.getLng() != null && a.getLat() != null) {
                            pts.add(new double[]{a.getLng(), a.getLat()});
                        }
                    });
        }
        if (pts.isEmpty()) {
            return null;
        }
        return new double[]{
                pts.stream().mapToDouble(p -> p[0]).average().orElse(0),
                pts.stream().mapToDouble(p -> p[1]).average().orElse(0)
        };
    }

    // ==================== 景点候选 ====================

    /** 生成景点备选池（规则秒出；有额外要求时带要求调 AI 重筛）；返回 AI 是否失败（true=成功或未使用） */
    public boolean generateAttractions(TravelState state) {
        List<Attraction> all = attractionMapper.selectList(new LambdaQueryWrapper<Attraction>()
                .eq(Attraction::getDestinationId, state.getDestinationId())
                .eq(Attraction::getStatus, 1));
        HardConstraintEvaluator.HardPolicy policy = buildHardPolicy(state);
        String city = state.getDestinationName();

        // S07：带上限的分批扫描，先硬过滤再截断——合格项可能在全量排序的任何位置，
        // 禁止先 limit 前 N 条再让 AI/规则从截断里找（P1_01_03 反例）。
        // 需求标签匹配优先进池：命中标签的景点不被池上限截掉
        List<String> needTags = state.getNeedTags() == null ? List.of() : state.getNeedTags();
        Map<Long, Attraction> attById = all.stream()
                .collect(Collectors.toMap(Attraction::getId, a -> a));
        TagEval tagEval = evaluateTags(needTags, all.stream().map(Attraction::getId).toList(),
                id -> attById.get(id) == null ? null : attById.get(id).getTags(),
                id -> {
                    Attraction a = attById.get(id);
                    return a == null ? "" : a.getName() + a.getCategory()
                            + (a.getFeatures() == null ? "" : a.getFeatures());
                },
                id -> {
                    Attraction a = attById.get(id);
                    return a != null && a.getTicketPrice() != null
                            && a.getTicketPrice().compareTo(BigDecimal.ZERO) == 0;
                });
        Comparator<Attraction> needFirst = Comparator
                .comparingInt((Attraction a) -> tagEval.hits().getOrDefault(a.getId(), 0)).reversed();
        List<Attraction> poolEntities = new ArrayList<>();
        Map<String, Object> evidence = new LinkedHashMap<>();
        Map<String, List<String>> acceptedByOrigin = new LinkedHashMap<>();
        List<String> sqlAccepted = new ArrayList<>();
        int scanned = 0;
        int unknown = 0;
        int locationRejected = 0;
        Comparator<Attraction> anchorFirst = Comparator.comparingDouble(a ->
                locationDistance(state, LocationConstraintSupport.ATTRACTION, a.getLng(), a.getLat()) == null
                        ? Double.MAX_VALUE
                        : locationDistance(state, LocationConstraintSupport.ATTRACTION, a.getLng(), a.getLat()));
        for (Attraction a : orderAttractions(all, state.getPreference()).stream()
                .sorted(needFirst.thenComparing(anchorFirst)).toList()) {
            scanned++;
            if (!locationEligible(state, LocationConstraintSupport.ATTRACTION,
                    a.getName(), a.getAddress(), a.getLng(), a.getLat())) {
                locationRejected++;
                continue;
            }
            HardConstraintEvaluator.Verdict v = HardConstraintEvaluator.evaluate(attractionFact(a, city), policy);
            if (v.status() == HardConstraintEvaluator.Status.ELIGIBLE) {
                poolEntities.add(a);
                sqlAccepted.add(placeKey("ATTRACTION", a.getId()));
                if (poolEntities.size() >= ATTRACTION_POOL_MAX) {
                    break;
                }
            } else if (v.status() == HardConstraintEvaluator.Status.UNKNOWN) {
                unknown++;
            }
        }
        acceptedByOrigin.put("SQL", sqlAccepted);
        List<AttractionCandidate> pool = poolEntities.stream()
                .map(a -> attractionOf(state, a, "综合推荐")).collect(Collectors.toCollection(ArrayList::new));

        boolean aiOk = true;
        List<Attraction> finalEntities = poolEntities;
        String req = channelRequest(state, CandidateChannelCoordinator.CHANNEL_ATTRACTION);
        if (req != null && !req.isBlank()) {
            List<Attraction> refined = refineAttractionsByAi(state, poolEntities, pool, req);
            if (refined != null) {
                boolean suppressed = false;
                if (refined.isEmpty()) {
                    // AI 表示池内无匹配（如地点限制）：保留规则池兜底展示，同时自动联网检索补候选
                    evidence.put("aiNoMatch", true);
                    suppressed = triggerWebSearchForAttractions(state, Set.of(), ATTRACTION_SEARCH_FLOOR);
                } else if (refined.size() < ATTRACTION_SEARCH_FLOOR) {
                    // 确定性触发：AI 精挑数量低于下限即视为覆盖不足，直接联网检索扩充——
                    // 不依赖 advice 文本措辞（措辞多变会漏触发）
                    evidence.put("aiInsufficient", true);
                    Set<Long> refinedIds = refined.stream().map(Attraction::getId).collect(Collectors.toSet());
                    suppressed = triggerWebSearchForAttractions(state, refinedIds,
                            ATTRACTION_SEARCH_FLOOR - refined.size());
                }
                // AI 精挑通常只有几家：先并入本会话已通过校验入库的网搜景点（与 KB 景点同等对待），
                // 仍不足下限时再用规则池补足（补足项同样已经过硬过滤）
                finalEntities = new ArrayList<>(refined);
                Set<Long> chosenIds = refined.stream().map(Attraction::getId).collect(Collectors.toSet());
                List<String> aiAccepted = refined.stream()
                        .map(a -> placeKey("ATTRACTION", a.getId())).toList();
                List<String> webAccepted = new ArrayList<>();
                List<String> backfillAccepted = new ArrayList<>();
                List<AttractionCandidate> extraPool = new ArrayList<>();
                // 网搜补充池：本会话新搜结果在前；跨会话复用（历史验证过的网搜景点）在后（功能隔离：未装配时只有前者）
                List<Attraction> webPool = new ArrayList<>(webExpandedAttractions(state, poolEntities));
                Set<Long> webPoolIds = webPool.stream().map(Attraction::getId).collect(Collectors.toSet());
                if (promotion != null) {
                    for (Attraction w : promotion.reusableWebAttractions(state.getDestinationId(),
                            java.time.LocalDateTime.now())) {
                        if (webPoolIds.add(w.getId())) {
                            webPool.add(w);
                        }
                    }
                }
                for (Attraction w : webPool) {
                    if (finalEntities.size() >= ATTRACTION_SEARCH_FLOOR) {
                        break;
                    }
                    if (!locationEligible(state, LocationConstraintSupport.ATTRACTION,
                            w.getName(), w.getAddress(), w.getLng(), w.getLat())) {
                        locationRejected++;
                        continue;
                    }
                    if (chosenIds.add(w.getId())) {
                        finalEntities.add(w);
                        extraPool.add(attractionOf(state, w, "联网检索补充"));
                        webAccepted.add(placeKey("ATTRACTION", w.getId()));
                        if (promotion != null) {
                            // 晋升计数是旁路功能：任何异常都不阻断候选主流程
                            try {
                                promotion.recordRecommend(FoodPromotionService.TYPE_ATTRACTION,
                                        state.getSessionId(), w.getId(), state.getDestinationId());
                            } catch (Exception e) {
                                log.warn("网搜景点推荐计数失败（{}），已降级跳过", e.getClass().getSimpleName());
                            }
                        }
                    }
                }
                if (!webAccepted.isEmpty()) {
                    String adv = state.getCandidateAdvice();
                    state.setCandidateAdvice((adv == null || adv.isBlank() ? "" : adv.trim() + " ")
                            + (suppressed
                                ? "已复用历史网搜结果补充 " + webAccepted.size() + " 个符合要求的景点。"
                                : "已联网检索扩充 " + webAccepted.size() + " 个符合要求的景点。"));
                } else if (Boolean.TRUE.equals(evidence.get("aiInsufficient"))
                        || Boolean.TRUE.equals(evidence.get("aiNoMatch"))) {
                    // 触发过检索但没有新条目并入（失败或全部被校验拒绝）：保持既有文案；
                    // 仅当检索被「历史复用抑制」跳过时使用新文案，两种场景都如实告知
                    String adv = state.getCandidateAdvice();
                    state.setCandidateAdvice((adv == null || adv.isBlank() ? "" : adv.trim() + " ")
                            + (suppressed
                                ? "（本次未获取到新的联网结果，已按备选池给出结果，可稍后重试）"
                                : "（联网检索暂不可用，本次按备选池给出结果，可稍后重试）"));
                }
                for (Attraction a : poolEntities) {
                    if (finalEntities.size() >= ATTRACTION_SEARCH_FLOOR) {
                        break;
                    }
                    if (chosenIds.add(a.getId())) {
                        finalEntities.add(a);
                        extraPool.add(attractionOf(state, a, "综合推荐"));
                        backfillAccepted.add(placeKey("ATTRACTION", a.getId()));
                    }
                }
                acceptedByOrigin.put("AI", aiAccepted);
                acceptedByOrigin.put("WEB", webAccepted);
                acceptedByOrigin.put("BACKFILL", backfillAccepted);
                // 以最终实体重建展示池（保留 AI 精选项的 why 文案，网搜/补足项按最终顺序并入）
                Map<Long, AttractionCandidate> whyById = new LinkedHashMap<>();
                pool.forEach(c -> whyById.putIfAbsent(c.getAttractionId(), c));
                extraPool.forEach(c -> whyById.putIfAbsent(c.getAttractionId(), c));
                pool.clear();
                for (Attraction a : finalEntities) {
                    pool.add(whyById.getOrDefault(a.getId(), attractionOf(state, a, "综合推荐")));
                }
            } else {
                aiOk = false;
                acceptedByOrigin.put("BACKFILL", sqlAccepted);
            }
        } else {
            state.setCandidateAdvice(null);
        }
        // AHP 基础分 + 标签加成（命中加分/冲突减分）= 展示综合分
        Map<String, Double> w = weightsOf(state);
        Map<Long, Double> scores = CandidateScorer.scoreAttractions(finalEntities, state.getPreference(), w);
        for (AttractionCandidate c : pool) {
            double bonus = tagEval.bonus().getOrDefault(c.getAttractionId(), 0.0);
            c.setScore(round(scores.getOrDefault(c.getAttractionId(), 0.0) + bonus
                    + locationBonus(state, LocationConstraintSupport.ATTRACTION, c.getDistanceToAnchor())));
            String note = tagEval.notes().get(c.getAttractionId());
            if (note != null && !note.isBlank()) {
                c.setTagNote(note);
            }
        }
        if (!needTags.isEmpty()) {
            // 需求标签匹配加权：标签命中数优先（需求信号压过基础评分），同命中数再按综合评分降序
            pool.sort(Comparator
                    .comparingInt((AttractionCandidate c) -> tagEval.hits().getOrDefault(c.getAttractionId(), 0))
                    .reversed()
                    .thenComparing(Comparator.comparingDouble(AttractionCandidate::getScore).reversed()));
        } else {
            pool.sort(Comparator.comparingDouble(AttractionCandidate::getScore).reversed());
        }
        state.setAttractionPool(pool);
        state.setAttractionCursor(0);
        // S07：锁定项与推荐池分离——池重建不通过 retainAll 清空已锁定地点
        evidence.put("scannedCount", scanned);
        evidence.put("eligibleCount", finalEntities.size());
        evidence.put("unknownCount", unknown);
        evidence.put("locationRejectedCount", locationRejected);
        evidence.put("acceptedByOrigin", acceptedByOrigin);
        sanitizeCandidateAdvice(state, "景点");
        newSnapshot(state, "ATTRACTION",
                finalEntities.stream().map(a -> placeKey("ATTRACTION", a.getId())).toList(), evidence);
        obsRecall(null, state, "ATTRACTION", evidence);
        nextAttractionBatch(state);
        state.rememberCandidateAdvice(CandidateChannelCoordinator.CHANNEL_ATTRACTION);
        log.info("[Candidate] 景点备选池 {} 个（会话 {}，AI失败={}）", pool.size(), state.getSessionId(), !aiOk);
        return aiOk;
    }

    /** 额外要求存在时：让 AttractionAgent 带着要求从池中重筛（失败返回 null 沿用规则池） */
    private List<Attraction> refineAttractionsByAi(TravelState state, List<Attraction> poolEntities,
                                                   List<AttractionCandidate> pool, String request) {
        String raw = null;
        ContextObservation observation = ContextObservation.disabled();
        try {
            String poolJson = objectMapper.writeValueAsString(
                    poolEntities.stream().map(a -> attractionPoolItem(state, a)).toList());
            int target = Math.min(LLM_SELECT_MAX, poolEntities.size());
            String prefJson = prefJson(state, request);
            AgentContextInvocation invocation = agentContextRuntime == null
                    || !agentContextRuntime.isActive(AgentContextRole.ATTRACTION) ? null
                    : agentContextRuntime.candidate(state, AgentContextRole.ATTRACTION,
                    poolJson, prefJson, target);
            if (invocation != null) {
                poolJson = invocation.argument("pool");
                prefJson = invocation.argument("preference");
                target = Integer.parseInt(invocation.argument("target"));
            }
            observation = beginCandidateContext(state, "AttractionAgent", "candidate-attraction-v1",
                    poolJson, prefJson, invocation);
            final String poolArg = poolJson;
            final String prefArg = prefJson;
            final int targetArg = target;
            Traced<String> traced = withTrace(state, "AttractionAgent", "景点AI重筛",
                    observation,
                    () -> attractionAgent.select(poolArg, prefArg, targetArg));
            raw = traced.content();
            AgentTrace span = traced.span();
            JsonNode node = JsonUtils.readTree(raw);
            // S12：解析结果分层记录
            span.setParseStatus(AgentTrace.SUCCESS);
            ArrayNode itemsNode = AgentOutputParser.extractItems(
                    node, AgentOutputParser::attractionItem, "attractionId", "id");
            Map<Long, Attraction> byId = poolEntities.stream()
                    .collect(Collectors.toMap(Attraction::getId, a -> a));
            List<AttractionCandidate> refined = new ArrayList<>();
            List<Attraction> refinedEntities = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            HardConstraintEvaluator.HardPolicy policy = buildHardPolicy(state);
            String city = state.getDestinationName();
            for (AttractionCandidate c : JsonUtils.parseList(itemsNode.toString(), AttractionCandidate.class)) {
                Attraction a = byId.get(c.getAttractionId());
                if (a == null || !seen.add(a.getId())) {
                    continue;
                }
                // S07：AI 出口同样必须过统一硬过滤——模型标签不能替代官方事实
                HardConstraintEvaluator.Verdict v =
                        HardConstraintEvaluator.evaluate(attractionFact(a, city), policy);
                if (v.status() != HardConstraintEvaluator.Status.ELIGIBLE) {
                    log.warn("景点候选 AI 输出项 {} 未通过硬过滤（{}），已剔除", a.getId(), v.reasonCode());
                    continue;
                }
                AttractionCandidate full = attractionOf(state, a,
                        c.getWhy() == null || c.getWhy().isBlank() ? "AI 推荐" : c.getWhy());
                refined.add(full);
                refinedEntities.add(a);
                if (refined.size() >= LLM_SELECT_MAX) {
                    break;
                }
            }
            if (refined.isEmpty() && itemsNode.size() > 0) {
                // S03：全部 ID 无效（池外/非法/硬约束）→ 降级且清除未验证 advice，不把模型自由文本当事实展示
                // S12：模型成功但语义失败——validation=FAILED + 回退原因 + 业务结果 DEGRADED
                span.setValidationStatus(AgentTrace.FAILED);
                span.setFallbackReason("HARD_CONSTRAINT_VIOLATION");
                traced.ctx().setBusinessStatus("DEGRADED");
                state.setCandidateAdvice(null);
                contextBaselineRecorder.parseFinished(observation, "SUCCESS", "FAILED",
                        "HARD_CONSTRAINT_VIOLATION");
                log.warn("景点候选 AI 输出解析后有效项为 0（输出形状或 id 与池不符），原始输出前300字：{}", snippet(raw));
                return null;
            }
            if (refined.isEmpty()) {
                // AI 明确表示池内无匹配（如地点限制）：这是合法语义结果而非失败——
                // 返回空列表，调用方保留规则池兜底展示并触发联网检索补候选（与美食通道同口径）
                span.setValidationStatus(AgentTrace.SUCCESS);
                span.setFallbackReason("POOL_NO_MATCH");
                traced.ctx().setBusinessStatus("DEGRADED");
                contextBaselineRecorder.parseFinished(observation, "SUCCESS", "SUCCESS", "POOL_NO_MATCH");
                log.warn("景点候选 AI 表示池内无匹配（items 为空），将保留规则池并尝试联网检索。原始输出前300字：{}", snippet(raw));
                return new ArrayList<>();
            }
            span.setValidationStatus(AgentTrace.SUCCESS);
            traced.ctx().setBusinessStatus("COMMITTED");
            state.setCandidateAdvice(AgentOutputParser.adviceOf(node));
            pool.clear();
            pool.addAll(refined);
            contextBaselineRecorder.parseFinished(observation, "SUCCESS", "SUCCESS", null);
            return refinedEntities;
        } catch (Exception e) {
            contextBaselineRecorder.parseFinished(observation, "FAILED", "UNKNOWN",
                    e.getClass().getSimpleName());
            state.setCandidateAdvice(null);
            log.warn("景点候选 AI 重筛失败（{}），沿用知识库规则池", e.getClass().getName(), e);
            log.warn("  AttractionAgent 原始输出前300字：{}", snippet(raw));
            return null;
        }
    }

    /** 换一批：从备选池翻下一页（已勾选置顶、不重复）；返回 0=新页 1=翻完一轮从头再来 */
    public int nextAttractionBatch(TravelState state) {
        List<AttractionCandidate> pool = state.getAttractionPool() == null
                ? List.of() : state.getAttractionPool();
        Set<Long> picked = state.getPickedAttractionIds();
        List<AttractionCandidate> pickedItems = pool.stream()
                .filter(c -> picked.contains(c.getAttractionId())).toList();
        List<AttractionCandidate> unpicked = pool.stream()
                .filter(c -> !picked.contains(c.getAttractionId())).toList();

        int cursor = state.getAttractionCursor();
        int wrapped = 0;
        if (cursor >= unpicked.size()) {
            cursor = 0;
            wrapped = 1;
        }
        List<AttractionCandidate> batch = new ArrayList<>(pickedItems);
        int need = Math.max(0, ATTRACTION_BATCH - batch.size());
        int take = Math.min(need, unpicked.size() - cursor);
        if (take > 0) {
            batch.addAll(unpicked.subList(cursor, cursor + take));
            cursor += take;
        }
        state.setAttractionCursor(cursor);
        state.setAttractionCandidates(batch);
        return wrapped;
    }

    /** 当前候选阶段是否确实还有未展示条目，供前端决定是否显示“换一批”。 */
    public boolean hasMoreCandidates(TravelState state) {
        if (state == null || state.getStage() == null) {
            return false;
        }
        return switch (state.getStage()) {
            case ATTRACTIONS -> {
                List<AttractionCandidate> pool = state.getAttractionPool() == null
                        ? List.of() : state.getAttractionPool();
                long remaining = pool.stream()
                        .filter(c -> !state.getPickedAttractionIds().contains(c.getAttractionId())).count();
                yield state.getAttractionCursor() < remaining;
            }
            case FOODS -> {
                List<FoodCandidate> pool = state.getFoodPool() == null ? List.of() : state.getFoodPool();
                long remaining = pool.stream().flatMap(g -> g.getRestaurants().stream())
                        .filter(c -> !state.getPickedFoodIds().contains(c.getRestaurantId())).count();
                yield state.getFoodCursor() < remaining;
            }
            case HOTELS -> {
                List<HotelCandidate> pool = state.getHotelPool() == null ? List.of() : state.getHotelPool();
                long remaining = pool.stream()
                        .filter(c -> !state.getPickedHotelIds().contains(c.getHotelId())).count();
                yield state.getHotelCursor() < remaining;
            }
            default -> false;
        };
    }

    private List<Attraction> orderAttractions(List<Attraction> all, TravelPreference p) {
        List<String> preferred = switch (p.getAttractionType() == null ? "" : p.getAttractionType()) {
            case "打卡拍照" -> List.of("打卡拍照", "文化历史");
            case "娱乐项目" -> List.of("娱乐项目");
            default -> List.of();
        };
        Comparator<Attraction> cmp = Comparator
                .comparingInt((Attraction a) -> preferred.contains(a.getCategory()) ? 0 : 1)
                .thenComparing(Attraction::getRating, Comparator.reverseOrder());
        return all.stream().sorted(cmp).toList();
    }

    private Map<String, Object> attractionPoolItem(TravelState state, Attraction a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("name", a.getName());
        m.put("category", a.getCategory());
        m.put("features", a.getFeatures() == null ? "" : a.getFeatures());
        m.put("intensity", a.getIntensity());
        m.put("hours", a.getSuggestHours());
        m.put("price", a.getTicketPrice());
        m.put("rating", a.getRating());
        m.put("address", a.getAddress() == null ? "" : a.getAddress());
        m.put("distanceToAnchorKm",
                locationDistance(state, LocationConstraintSupport.ATTRACTION, a.getLng(), a.getLat()));
        return m;
    }

    private AttractionCandidate attractionOf(TravelState state, Attraction a, String why) {
        AttractionCandidate c = new AttractionCandidate();
        c.setAttractionId(a.getId());
        c.setName(a.getName());
        c.setFeature(a.getFeatures());
        c.setTags(a.getTags());
        c.setWhy(why);
        c.setDistanceToAnchor(
                locationDistance(state, LocationConstraintSupport.ATTRACTION, a.getLng(), a.getLat()));
        return c;
    }

    // ==================== 美食候选 ====================

    /** 生成美食备选池（按风味分组、规则秒出；有额外要求时带要求调 AI 重筛）；返回 AI 是否失败 */
    public boolean generateFoods(TravelState state) {
        List<Restaurant> all = restaurantMapper.selectList(new LambdaQueryWrapper<Restaurant>()
                .eq(Restaurant::getDestinationId, state.getDestinationId())
                .eq(Restaurant::getStatus, 1));
        HardConstraintEvaluator.HardPolicy policy = buildHardPolicy(state);
        String city = state.getDestinationName();
        TravelPreference pref = state.getPreference();
        ResolvedPlanningPolicy planning = PlanningPolicyResolver.resolve(state);
        boolean mealActive = isExplicitMeal(planning.getMeal().getLunch())
                || isExplicitMeal(planning.getMeal().getDinner())
                || planning.getMeal().isExplicitMealComposition()
                || planning.getMeal().getLunchCandidateCount() != null
                || planning.getMeal().getDinnerCandidateCount() != null;
        // 明确提出餐次/正餐需求（如「2顿午饭1顿晚饭」「不要小吃」）→ 默认不含小吃；显式「要小吃」可覆盖
        boolean excludeSnacks = !planning.getMeal().isSnacksAllowed();

        // S07：分组前先统一硬过滤（禁止截断后过滤）
        Map<String, List<Restaurant>> groups = new LinkedHashMap<>();
        Map<String, Object> evidence = new LinkedHashMap<>();
        Map<String, List<String>> acceptedByOrigin = new LinkedHashMap<>();
        BigDecimal foodCap = foodPriceCap(state);
        int scanned = 0;
        int unknown = 0;
        int overCap = 0;
        int locationRejected = 0;
        for (Restaurant r : orderRestaurants(all, pref)) {
            scanned++;
            if (!locationEligible(state, LocationConstraintSupport.FOOD,
                    r.getName(), r.getAddress(), r.getLng(), r.getLat())) {
                locationRejected++;
                continue;
            }
            if (excludeSnacks && MealPolicySupport.isSnack(r)) {
                continue;
            }
            HardConstraintEvaluator.Verdict v = HardConstraintEvaluator.evaluate(restaurantFact(r, city), policy);
            if (v.status() != HardConstraintEvaluator.Status.ELIGIBLE) {
                if (v.status() == HardConstraintEvaluator.Status.UNKNOWN) {
                    unknown++;
                }
                continue;
            }
            // 字段级美食人均上限（「美食要人均50以内」）：人均超出上限的店不进池
            if (foodCap != null && r.getAvgPrice() != null && r.getAvgPrice().compareTo(foodCap) > 0) {
                overCap++;
                continue;
            }
            groups.computeIfAbsent(r.getCuisine(), k -> new ArrayList<>()).add(r);
        }
        List<Restaurant> poolEntities = new ArrayList<>();
        outer:
        for (List<Restaurant> list : groups.values()) {
            for (Restaurant r : list.stream().limit(FOOD_PER_GROUP).toList()) {
                if (poolEntities.size() >= FOOD_POOL_MAX) {
                    break outer;
                }
                poolEntities.add(r);
            }
        }
        acceptedByOrigin.put("SQL", poolEntities.stream()
                .map(r -> placeKey("FOOD", r.getId())).toList());
        Map<Long, String> mealTypes = new LinkedHashMap<>();
        if (mealActive) {
            assignMealTypes(poolEntities, planning, daysOf(state), excludeSnacks, mealTypes);
        }
        List<FoodCandidate> pool = new ArrayList<>(groupItems(state, poolEntities, mealTypes));

        // 目标数量：默认 1 天 3 餐（早餐 3×天数 + 正餐 6×天数），不足 15 按 15（candidate.food 可配置）；
        // 用户明确提出餐次结构时按「各餐顿数 × 天数」计算，尊重需求不再用 15 保底
        int target;
        if (mealActive) {
            int requiredMeals = mealEventCount(planning.getMeal().getLunch(), daysOf(state))
                    + mealEventCount(planning.getMeal().getDinner(), daysOf(state));
            int requiredComposition = mealEventCount(planning.getMeal().getSnack(), daysOf(state))
                    + mealEventCount(planning.getMeal().getMainMeal(), daysOf(state));
            int requestedCandidates = Math.max(
                    planning.getMeal().getLunchCandidateCount() == null ? 0
                            : planning.getMeal().getLunchCandidateCount(),
                    planning.getMeal().getDinnerCandidateCount() == null ? 0
                            : planning.getMeal().getDinnerCandidateCount());
            target = Math.min(Math.max(Math.max(Math.max(requiredMeals, requiredComposition), requestedCandidates), 1),
                    poolEntities.size());
        } else {
            target = foodConfig.resolveTarget(daysOf(state), poolEntities.size());
        }

        boolean aiOk = true;
        List<Restaurant> finalEntities = poolEntities;
        String req = channelRequest(state, CandidateChannelCoordinator.CHANNEL_FOOD);
        if (req != null && !req.isBlank()) {
            List<Restaurant> refined = refineFoodsByAi(state, poolEntities, target, mealTypes, req);
            if (refined != null) {
                boolean suppressed = false;
                if (refined.isEmpty()) {
                    // AI 表示池内无匹配（如地点限制）：保留规则池兜底展示，同时自动联网检索补候选
                    evidence.put("aiNoMatch", true);
                    suppressed = triggerWebSearchOnNoMatch(state, Set.of(), target);
                } else if (refined.size() < target) {
                    // 确定性触发：AI 精挑数量低于目标即视为覆盖不足（如「园区仅 1 家符合选址，
                    // 其余补位」），直接联网检索扩充——不依赖 advice 文本措辞（措辞多变会漏触发）
                    evidence.put("aiInsufficient", true);
                    Set<Long> refinedIds = refined.stream().map(Restaurant::getId).collect(Collectors.toSet());
                    suppressed = triggerWebSearchOnNoMatch(state, refinedIds, target - refined.size());
                }
                // AI 精挑通常只有 2~3 家：先并入本会话已通过校验入库的网搜店（与 KB 店同等对待），
                // 仍不足目标数量时再用规则池补足（补足项同样已经过硬过滤与小吃排除）
                finalEntities = new ArrayList<>(refined);
                Set<Long> chosenIds = refined.stream().map(Restaurant::getId).collect(Collectors.toSet());
                List<String> aiAccepted = refined.stream().map(r -> placeKey("FOOD", r.getId())).toList();
                List<String> webAccepted = new ArrayList<>();
                List<String> backfillAccepted = new ArrayList<>();
                // 网搜补充池：本会话新搜结果在前；跨会话复用（历史验证过的网搜店）在后（功能隔离：未装配时只有前者）
                List<Restaurant> webPool = new ArrayList<>(webExpandedRestaurants(state, poolEntities));
                Set<Long> webPoolIds = webPool.stream().map(Restaurant::getId).collect(Collectors.toSet());
                if (promotion != null) {
                    for (Restaurant w : promotion.reusableWebRestaurants(state.getDestinationId(),
                            java.time.LocalDateTime.now())) {
                        if (webPoolIds.add(w.getId())) {
                            webPool.add(w);
                        }
                    }
                }
                for (Restaurant w : webPool) {
                    if (finalEntities.size() >= target) {
                        break;
                    }
                    if (!locationEligible(state, LocationConstraintSupport.FOOD,
                            w.getName(), w.getAddress(), w.getLng(), w.getLat())) {
                        locationRejected++;
                        continue;
                    }
                    if (chosenIds.add(w.getId())) {
                        finalEntities.add(w);
                        webAccepted.add(placeKey("FOOD", w.getId()));
                        if (promotion != null) {
                            // 晋升计数是旁路功能：任何异常都不阻断候选主流程（表缺失/并发冲突等降级为不计数）
                            try {
                                promotion.recordRecommend(state.getSessionId(), w.getId(), state.getDestinationId());
                            } catch (Exception e) {
                                log.warn("网搜店推荐计数失败（{}），已降级跳过", e.getClass().getSimpleName());
                            }
                        }
                    }
                }
                if (!webAccepted.isEmpty()) {
                    String adv = state.getCandidateAdvice();
                    state.setCandidateAdvice((adv == null || adv.isBlank() ? "" : adv.trim() + " ")
                            + (suppressed
                                ? "已复用历史网搜结果补充 " + webAccepted.size() + " 家符合要求的店铺。"
                                : "已联网检索扩充 " + webAccepted.size() + " 家符合要求的店铺。"));
                } else if (Boolean.TRUE.equals(evidence.get("aiInsufficient"))
                        || Boolean.TRUE.equals(evidence.get("aiNoMatch"))) {
                    // 触发过检索但没有新条目并入（失败或全部被校验拒绝）：保持既有文案；
                    // 仅当检索被「历史复用抑制」跳过时使用新文案，两种场景都如实告知
                    String adv = state.getCandidateAdvice();
                    state.setCandidateAdvice((adv == null || adv.isBlank() ? "" : adv.trim() + " ")
                            + (suppressed
                                ? "（本次未获取到新的联网结果，已按备选池给出结果，可稍后重试）"
                                : "（联网检索暂不可用，本次按备选池给出结果，可稍后重试）"));
                }
                for (Restaurant r : poolEntities) {
                    if (finalEntities.size() >= target) {
                        break;
                    }
                    if (!chosenIds.contains(r.getId())) {
                        finalEntities.add(r);
                        backfillAccepted.add(placeKey("FOOD", r.getId()));
                    }
                }
                acceptedByOrigin.put("AI", aiAccepted);
                acceptedByOrigin.put("WEB", webAccepted);
                acceptedByOrigin.put("BACKFILL", backfillAccepted);
            } else {
                aiOk = false;
                acceptedByOrigin.put("BACKFILL", acceptedByOrigin.get("SQL"));
            }
        } else {
            state.setCandidateAdvice(null);
        }
        // 以最终实体重建分组池（AI 精选在前 + 补足项），随后按口味档位优先、档内按 AHP 评分降序
        pool.clear();
        pool.addAll(groupItems(state, finalEntities, mealTypes));
        // AHP 基础分 + 标签加成（命中加分/冲突减分）= 展示综合分
        Map<String, Double> w = weightsOf(state);
        Map<Long, Double> scores = CandidateScorer.scoreRestaurants(
                finalEntities, selectedAttractionCentroid(state), state.getPreference(), w);
        List<String> needTags = state.getNeedTags() == null ? List.of() : state.getNeedTags();
        Map<Long, Restaurant> resById = finalEntities.stream()
                .collect(Collectors.toMap(Restaurant::getId, r -> r));
        TagEval foodTagEval = evaluateTags(needTags, finalEntities.stream().map(Restaurant::getId).toList(),
                id -> resById.get(id) == null ? null : resById.get(id).getTags(),
                id -> {
                    Restaurant r = resById.get(id);
                    return r == null ? "" : r.getName() + r.getCuisine()
                            + (r.getSignatureDish() == null ? "" : r.getSignatureDish());
                },
                id -> false);
        for (FoodCandidate g : pool) {
            for (FoodCandidate.FoodItem it : g.getRestaurants()) {
                double bonus = foodTagEval.bonus().getOrDefault(it.getRestaurantId(), 0.0);
                it.setScore(round(scores.getOrDefault(it.getRestaurantId(), 0.0) + bonus
                        + locationBonus(state, LocationConstraintSupport.FOOD, it.getDistanceToAnchor())));
                String note = foodTagEval.notes().get(it.getRestaurantId());
                if (note != null && !note.isBlank()) {
                    it.setTagNote(note);
                }
            }
            g.getRestaurants().sort(Comparator.comparingDouble(FoodCandidate.FoodItem::getScore).reversed());
        }
        // 默认口味档位优先：与用户口味匹配的风味整体排前（如「本地特色菜」→ 本地菜在小吃之前），
        // 档内再按组内最高分降序——避免成本导向的高分小吃霸占第一页
        List<String> tasteTiers = preferredCuisines(pref);
        // 响应式排序：用户提出平价诉求时按综合评分直排（成本已加权，便宜小吃自然靠前）；
        // 想吃小吃时小吃档位提前——让「不要贵的 / 想吃特色小吃」立刻反映在第一页
        if (priceSensitive(state)) {
            pool.sort(Comparator.comparingDouble(
                    (FoodCandidate g) -> g.getRestaurants().isEmpty()
                            ? 0 : g.getRestaurants().get(0).getScore()).reversed());
        } else {
            List<String> tiers = wantsSnacks(state) ? snackFirstTiers(tasteTiers) : tasteTiers;
            pool.sort(Comparator
                    .comparingInt((FoodCandidate g) -> {
                        int i = tiers.indexOf(g.getCuisine());
                        return i < 0 ? tiers.size() : i;
                    })
                    .thenComparing(Comparator.comparingDouble(
                            (FoodCandidate g) -> g.getRestaurants().isEmpty()
                                    ? 0 : g.getRestaurants().get(0).getScore()).reversed()));
        }
        state.setFoodPool(pool);
        state.setFoodCursor(0);
        Set<Long> poolIds = pool.stream().flatMap(g -> g.getRestaurants().stream())
                .map(FoodCandidate.FoodItem::getRestaurantId).collect(Collectors.toSet());
        // S07：锁定项与推荐池分离——池重建不通过 retainAll 清空已锁定地点
        evidence.put("scannedCount", scanned);
        evidence.put("eligibleCount", poolIds.size());
        evidence.put("unknownCount", unknown);
        evidence.put("locationRejectedCount", locationRejected);
        evidence.put("acceptedByOrigin", acceptedByOrigin);
        sanitizeCandidateAdvice(state, "餐厅");
        newSnapshot(state, "FOOD",
                finalEntities.stream().map(r -> placeKey("FOOD", r.getId())).toList(), evidence);
        obsRecall(null, state, "FOOD", evidence);
        nextFoodBatch(state);
        state.rememberCandidateAdvice(CandidateChannelCoordinator.CHANNEL_FOOD);
        log.info("[Candidate] 美食备选池 {} 家（会话 {}，AI失败={}）", poolIds.size(), state.getSessionId(), !aiOk);
        return aiOk;
    }

    /** 额外要求存在时：让 FoodAgent 带着要求重筛（失败返回 null 沿用规则池）；AI 可修正各店餐次归类 */
    private List<Restaurant> refineFoodsByAi(TravelState state, List<Restaurant> poolEntities,
                                             int target, Map<Long, String> mealTypes, String request) {
        String raw = null;
        ContextObservation observation = ContextObservation.disabled();
        try {
            String poolJson = objectMapper.writeValueAsString(
                    poolEntities.stream().map(r -> restaurantPoolItem(state, r)).toList());
            String prefJson = prefJson(state, request);
            AgentContextInvocation invocation = agentContextRuntime == null
                    || !agentContextRuntime.isActive(AgentContextRole.FOOD) ? null
                    : agentContextRuntime.candidate(state, AgentContextRole.FOOD,
                    poolJson, prefJson, target);
            if (invocation != null) {
                poolJson = invocation.argument("pool");
                prefJson = invocation.argument("preference");
                target = Integer.parseInt(invocation.argument("target"));
            }
            observation = beginCandidateContext(state, "FoodAgent", "candidate-food-v1",
                    poolJson, prefJson, invocation);
            final String poolArg = poolJson;
            final String prefArg = prefJson;
            final int targetArg = target;
            Traced<String> traced = withTrace(state, "FoodAgent", "美食AI重筛",
                    observation,
                    () -> foodAgent.select(poolArg, prefArg, targetArg));
            raw = traced.content();
            AgentTrace span = traced.span();
            JsonNode node = JsonUtils.readTree(raw);
            // S12：解析结果分层记录
            span.setParseStatus(AgentTrace.SUCCESS);
            ArrayNode itemsNode = AgentOutputParser.extractItems(
                    node, AgentOutputParser::foodItem, "restaurantId", "id");
            state.setCandidateAdvice(AgentOutputParser.adviceOf(node));
            Map<Long, Restaurant> byId = poolEntities.stream()
                    .collect(Collectors.toMap(Restaurant::getId, r -> r));
            List<Restaurant> refinedEntities = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            HardConstraintEvaluator.HardPolicy policy = buildHardPolicy(state);
            String city = state.getDestinationName();
            // 统一解析层已把输出归一为 [{"restaurantId":数字,"mealType":餐次}]，这里取 id 与餐次修正
            for (JsonNode it : itemsNode) {
                long id = it.path("restaurantId").asLong(0);
                Restaurant r = id == 0 ? null : byId.get(id);
                if (r == null || !seen.add(r.getId())) {
                    continue;
                }
                // S07：AI 出口同样必须过统一硬过滤——模型标签不能替代官方事实
                HardConstraintEvaluator.Verdict v =
                        HardConstraintEvaluator.evaluate(restaurantFact(r, city), policy);
                if (v.status() != HardConstraintEvaluator.Status.ELIGIBLE) {
                    log.warn("美食候选 AI 输出项 {} 未通过硬过滤（{}），已剔除", r.getId(), v.reasonCode());
                    continue;
                }
                String mt = normalizeMealType(it.path("mealType").asText(""));
                if (mt != null) {
                    mealTypes.put(r.getId(), mt);
                }
                refinedEntities.add(r);
                if (refinedEntities.size() >= LLM_SELECT_MAX) {
                    break;
                }
            }
            if (refinedEntities.isEmpty()) {
                // AI 明确表示池内无匹配（如地点限制）：这是合法语义结果而非失败——
                // 返回空列表，调用方保留规则池兜底展示并触发联网检索补候选
                span.setValidationStatus(AgentTrace.SUCCESS);
                span.setFallbackReason("POOL_NO_MATCH");
                traced.ctx().setBusinessStatus("DEGRADED");
                contextBaselineRecorder.parseFinished(observation, "SUCCESS", "SUCCESS", "POOL_NO_MATCH");
                log.warn("美食候选 AI 表示池内无匹配（items 为空），将保留规则池并尝试联网检索。原始输出前300字：{}", snippet(raw));
                return new ArrayList<>();
            }
            span.setValidationStatus(AgentTrace.SUCCESS);
            traced.ctx().setBusinessStatus("COMMITTED");
            contextBaselineRecorder.parseFinished(observation, "SUCCESS", "SUCCESS", null);
            return refinedEntities;
        } catch (Exception e) {
            contextBaselineRecorder.parseFinished(observation, "FAILED", "UNKNOWN",
                    e.getClass().getSimpleName());
            state.setCandidateAdvice(null);
            log.warn("美食候选 AI 重筛失败（{}），沿用知识库规则池", e.getClass().getName(), e);
            log.warn("  FoodAgent 原始输出前300字：{}", snippet(raw));
            return null;
        }
    }

    /**
     * 阶段2：知识库无法满足时联网检索真实店铺（enable_search），与知识库店名去重。
     * 失败或未找到返回 null（调用方降级为仅知识库推荐，不阻塞主流程）。
     */
    public List<WebFoodCandidate> searchFoodsOnline(TravelState state) {
        if (dashScopeSearchClient == null) {
            log.warn("[Candidate] 联网检索客户端不可用，跳过");
            return null;
        }
        long start = System.currentTimeMillis();
        String raw = null;
        // O1：搜索也是一次物理 provider attempt——独立 PROVIDER 叶子 span（与候选重筛 span 同口径）
        TraceContext sctx = traceService.newTrace(state.getSessionId(), "SearchAgent");
        sctx.setSpanId("SearchAgent-" + UUID.randomUUID());
        sctx.setKind("PROVIDER");
        traceService.register(sctx);
        try {
            String city = state.getDestinationName();
            String system = loadPrompt("prompts/search_food.txt");
            String user = "城市：" + city
                    + "\n用户的特殊需求：" + state.getExtraRequest()
                    + "\n用户偏好：" + objectMapper.writeValueAsString(state.getPreference());
            DashScopeSearchClient.SearchResult r = dashScopeSearchClient.search(system, user);
            raw = r.content();
            String actualSearchModel = r.model() == null ? searchModel : r.model();
            int promptTokens = r.promptTokens() == null ? 0 : r.promptTokens();
            int completionTokens = r.completionTokens() == null ? 0 : r.completionTokens();
            TokenUsage searchUsage = new TokenUsage(promptTokens, completionTokens);
            JsonNode node = JsonUtils.readTree(raw);
            Set<String> kbNames = restaurantMapper.selectList(new LambdaQueryWrapper<Restaurant>()
                            .eq(Restaurant::getDestinationId, state.getDestinationId())
                            .eq(Restaurant::getStatus, 1))
                    .stream().map(Restaurant::getName).collect(Collectors.toSet());
            List<WebFoodCandidate> items = new ArrayList<>();
            for (JsonNode it : node.path("items")) {
                String name = it.path("name").asText("").trim();
                if (name.isBlank() || items.size() >= WEB_FOOD_MAX) {
                    continue;
                }
                if (kbNames.stream().anyMatch(kb -> kb.contains(name) || name.contains(kb))) {
                    continue;
                }
                WebFoodCandidate w = new WebFoodCandidate();
                w.setName(name);
                w.setCuisine(it.path("cuisine").asText("").trim());
                try {
                    w.setAvgPrice(new BigDecimal(it.path("avgPrice").asText("0").trim()));
                } catch (NumberFormatException ignored) {
                    // 价格缺失：保留 null，由入库校验拒绝（价格未知的店不进知识库）
                }
                w.setAddress(it.path("address").asText("").trim());
                w.setWhy(it.path("why").asText("").trim());
                items.add(w);
            }
            // 入库前确定性校验 + 审计 + 扩充知识库：只返回通过校验并成功入库的条目
            List<WebFoodCandidate> accepted = ingestWebFood(state, items, kbNames);
            state.setWebFoodCandidates(accepted);
            long cost = System.currentTimeMillis() - start;
            AgentTrace sspan = AgentTrace.success("SearchAgent", cost, searchUsage);
            sspan.setAnswer(UsageService.clip(raw, 2000));
            sctx.add(sspan);
            sctx.finish("SUCCESS");
            // O1：搜索 token 与耗时如实落库（attempt 明细行），并归集到本轮（turn）
            String remark = "n=" + accepted.size()
                    + (accepted.size() < items.size() ? ",rejected=" + (items.size() - accepted.size()) : "");
            usageService.recordAgent(state.getSessionId(), state.getUserId(), state.getUsername(),
                    "FOODS", "美食联网检索", "SearchAgent", searchUsage, cost, "SUCCESS",
                    remark, actualSearchModel, UsageChannel.AGENT,
                    UsageService.clip(raw, 2000));
            state.addTurnUsage(actualSearchModel, promptTokens, completionTokens, UsageChannel.AGENT);
            return accepted.isEmpty() ? null : accepted;
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.warn("美食联网检索失败（{}），降级为仅知识库推荐", e.getClass().getName());
            AgentTrace sspan = AgentTrace.failure("SearchAgent", cost,
                    e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            sctx.add(sspan);
            sctx.finish("FAILED");
            usageService.recordAgent(state.getSessionId(), state.getUserId(), state.getUsername(),
                    "FOODS", "美食联网检索", "SearchAgent", null, cost, "FAILED",
                    e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()),
                    searchModel, UsageChannel.RULE_FALLBACK, null);
            return null;
        } finally {
            traceService.finish(sctx);
        }
    }

    /**
     * AI 明确表示池内无匹配或覆盖不足时：自动联网检索补充候选。
     * 同一需求只搜一次（会话内去重）；只有满足当前硬约束、未被本轮选中且数量足以补齐缺口的
     * 历史网搜店才能替代本次联网检索。其他片区或数量不足的历史结果不能造成“假复用”。
     * 返回 true 表示本次检索被「历史复用抑制」跳过（未发起检索）。
     */
    private boolean triggerWebSearchOnNoMatch(TravelState state, Set<Long> selectedIds, int requiredCount) {
        String req = channelRequest(state, CandidateChannelCoordinator.CHANNEL_FOOD);
        String key = "confirm:" + (req == null ? "" : req);
        if (key.equals(state.getWebSearchKey())) {
            return false;
        }
        if (promotion != null) {
            List<Restaurant> reusable = promotion.reusableWebRestaurants(state.getDestinationId(),
                    java.time.LocalDateTime.now());
            long eligible = reusable.stream()
                    .filter(r -> r.getId() != null && (selectedIds == null || !selectedIds.contains(r.getId())))
                    .filter(r -> reusableFoodEligible(state, r))
                    .count();
            if (eligible >= Math.max(1, requiredCount)) {
                log.info("[Food][sessionId={}] 有 {} 家历史网搜店满足当前约束并足以补齐 {} 家缺口，跳过本次联网检索",
                        state.getSessionId(), eligible, Math.max(1, requiredCount));
                return true;
            }
            if (!reusable.isEmpty()) {
                log.info("[Food][sessionId={}] 历史网搜店共 {} 家，但当前约束可复用 {} 家、不足补齐 {} 家缺口，继续联网检索",
                        state.getSessionId(), reusable.size(), eligible, Math.max(1, requiredCount));
            }
        }
        List<WebFoodCandidate> web = searchFoodsOnline(state);
        if (web != null && !web.isEmpty()) {
            // 仅在成功时登记去重键：失败不登记，用户重试同需求时允许再次尝试
            state.setWebSearchKey(key);
            state.setWebFoodCandidates(web);
        }
        return false;
    }

    /** 历史网搜店是否能在本轮直接复用：沿用当前候选池的地点、餐型、硬条件与价格上限口径。 */
    private boolean reusableFoodEligible(TravelState state, Restaurant restaurant) {
        if (restaurant == null || !locationEligible(state, LocationConstraintSupport.FOOD,
                restaurant.getName(), restaurant.getAddress(), restaurant.getLng(), restaurant.getLat())) {
            return false;
        }
        ResolvedPlanningPolicy planning = PlanningPolicyResolver.resolve(state);
        if (!planning.getMeal().isSnacksAllowed() && MealPolicySupport.isSnack(restaurant)) {
            return false;
        }
        if (HardConstraintEvaluator.evaluate(restaurantFact(restaurant, state.getDestinationName()),
                buildHardPolicy(state)).status() != HardConstraintEvaluator.Status.ELIGIBLE) {
            return false;
        }
        BigDecimal cap = foodPriceCap(state);
        return cap == null || restaurant.getAvgPrice() == null || restaurant.getAvgPrice().compareTo(cap) <= 0;
    }

    /**
     * 景点通道覆盖不足时联网补充。历史结果只有在通过当前位置/硬约束、排除已选后，
     * 数量足以补齐缺口时才可抑制新搜索，避免“一条无坐标旧记录阻断整轮检索”。
     */
    private boolean triggerWebSearchForAttractions(TravelState state, Set<Long> selectedIds, int requiredCount) {
        String req = channelRequest(state, CandidateChannelCoordinator.CHANNEL_ATTRACTION);
        String key = "confirm:" + (req == null ? "" : req);
        if (key.equals(state.getWebAttractionSearchKey())) {
            return false;
        }
        if (promotion != null) {
            List<Attraction> reusable = promotion.reusableWebAttractions(state.getDestinationId(),
                    java.time.LocalDateTime.now());
            long eligible = reusable.stream()
                    .filter(a -> a.getId() != null && (selectedIds == null || !selectedIds.contains(a.getId())))
                    .filter(a -> reusableAttractionEligible(state, a))
                    .count();
            int shortage = Math.max(1, requiredCount);
            if (eligible >= shortage) {
                log.info("[Attraction][sessionId={}] 有 {} 个历史网搜景点满足当前约束并足以补齐 {} 个缺口，跳过本次联网检索",
                        state.getSessionId(), eligible, shortage);
                return true;
            }
            if (!reusable.isEmpty()) {
                log.info("[Attraction][sessionId={}] 历史网搜景点共 {} 个，但当前约束可复用 {} 个、不足补齐 {} 个缺口，继续联网检索",
                        state.getSessionId(), reusable.size(), eligible, shortage);
            }
        }
        List<WebAttractionCandidate> web = searchAttractionsOnline(state);
        if (web != null && !web.isEmpty()) {
            state.setWebAttractionSearchKey(key);
            state.setWebAttractionCandidates(web);
        }
        return false;
    }

    private boolean reusableAttractionEligible(TravelState state, Attraction attraction) {
        return attraction != null
                && locationEligible(state, LocationConstraintSupport.ATTRACTION,
                attraction.getName(), attraction.getAddress(), attraction.getLng(), attraction.getLat())
                && HardConstraintEvaluator.evaluate(attractionFact(attraction, state.getDestinationName()),
                buildHardPolicy(state)).status() == HardConstraintEvaluator.Status.ELIGIBLE;
    }

    /** 酒店通道：AI 表示池内无匹配或覆盖不足时自动联网检索补充（会话内去重，语义与美食通道对称）。
     *  存在可复用历史网搜酒店时跳过本次检索。返回 true 表示本次检索被「历史复用抑制」跳过。 */
    private boolean triggerWebSearchForHotels(TravelState state) {
        String req = channelRequest(state, CandidateChannelCoordinator.CHANNEL_HOTEL);
        String key = "confirm:" + (req == null ? "" : req);
        if (key.equals(state.getWebHotelSearchKey())) {
            return false;
        }
        if (promotion != null
                && !promotion.reusableWebHotels(state.getDestinationId(),
                        java.time.LocalDateTime.now()).isEmpty()) {
            log.info("[Hotel][sessionId={}] 存在可复用历史网搜酒店，跳过本次联网检索", state.getSessionId());
            return true;
        }
        List<WebHotelCandidate> web = searchHotelsOnline(state);
        if (web != null && !web.isEmpty()) {
            state.setWebHotelSearchKey(key);
            state.setWebHotelCandidates(web);
        }
        return false;
    }

    /**
     * 景点通道：知识库无法满足时联网检索真实景点（enable_search），与知识库景点名去重。
     * 失败或未找到返回 null（调用方降级为仅知识库推荐，不阻塞主流程）。
     */
    public List<WebAttractionCandidate> searchAttractionsOnline(TravelState state) {
        if (dashScopeSearchClient == null) {
            log.warn("[Candidate] 联网检索客户端不可用，景点通道跳过");
            return null;
        }
        long start = System.currentTimeMillis();
        String raw = null;
        // O1：搜索也是一次物理 provider attempt——独立 PROVIDER 叶子 span（与候选重筛 span 同口径）
        TraceContext sctx = traceService.newTrace(state.getSessionId(), "AttractionSearchAgent");
        sctx.setSpanId("AttractionSearchAgent-" + UUID.randomUUID());
        sctx.setKind("PROVIDER");
        traceService.register(sctx);
        try {
            String city = state.getDestinationName();
            String system = loadPrompt("prompts/search_attraction.txt");
            String locationSummary = LocationConstraintSupport.summary(state.getLocationConstraint());
            String user = "城市：" + city
                    + "\n用户的特殊需求：" + state.getExtraRequest()
                    + (locationSummary == null ? "" : "\n必须满足的位置范围：" + locationSummary)
                    + "\n用户偏好：" + objectMapper.writeValueAsString(state.getPreference());
            DashScopeSearchClient.SearchResult r = dashScopeSearchClient.search(system, user);
            raw = r.content();
            String actualSearchModel = r.model() == null ? searchModel : r.model();
            int promptTokens = r.promptTokens() == null ? 0 : r.promptTokens();
            int completionTokens = r.completionTokens() == null ? 0 : r.completionTokens();
            TokenUsage searchUsage = new TokenUsage(promptTokens, completionTokens);
            JsonNode node = JsonUtils.readTree(raw);
            Set<String> kbNames = attractionMapper.selectList(new LambdaQueryWrapper<Attraction>()
                            .eq(Attraction::getDestinationId, state.getDestinationId())
                            .eq(Attraction::getStatus, 1))
                    .stream().map(Attraction::getName).collect(Collectors.toSet());
            List<WebAttractionCandidate> items = new ArrayList<>();
            for (JsonNode it : node.path("items")) {
                String name = it.path("name").asText("").trim();
                if (name.isBlank() || items.size() >= WEB_ATTRACTION_MAX) {
                    continue;
                }
                if (kbNames.stream().anyMatch(kb -> kb.contains(name) || name.contains(kb))) {
                    continue;
                }
                WebAttractionCandidate w = new WebAttractionCandidate();
                w.setName(name);
                w.setCategory(it.path("category").asText("").trim());
                w.setTags(it.path("tags").asText("").trim());
                try {
                    w.setTicketPrice(new BigDecimal(it.path("ticketPrice").asText("").trim()));
                } catch (NumberFormatException ignored) {
                    // 价格缺失：保留 null，由入库校验拒绝（价格未知的景点不进知识库）
                }
                w.setAddress(it.path("address").asText("").trim());
                if (it.hasNonNull("lng") && it.path("lng").isNumber()) {
                    w.setLng(it.path("lng").asDouble());
                }
                if (it.hasNonNull("lat") && it.path("lat").isNumber()) {
                    w.setLat(it.path("lat").asDouble());
                }
                w.setWhy(it.path("why").asText("").trim());
                items.add(w);
            }
            // 入库前确定性校验 + 审计 + 扩充知识库：只返回通过校验并成功入库的条目
            List<WebAttractionCandidate> accepted = ingestWebAttraction(state, items, kbNames);
            state.setWebAttractionCandidates(accepted);
            long cost = System.currentTimeMillis() - start;
            AgentTrace sspan = AgentTrace.success("AttractionSearchAgent", cost, searchUsage);
            sspan.setAnswer(UsageService.clip(raw, 2000));
            sctx.add(sspan);
            sctx.finish("SUCCESS");
            // O1：搜索 token 与耗时如实落库（attempt 明细行），并归集到本轮（turn）
            String remark = "n=" + accepted.size()
                    + (accepted.size() < items.size() ? ",rejected=" + (items.size() - accepted.size()) : "");
            usageService.recordAgent(state.getSessionId(), state.getUserId(), state.getUsername(),
                    "ATTRACTIONS", "景点联网检索", "AttractionSearchAgent", searchUsage, cost, "SUCCESS",
                    remark, actualSearchModel, UsageChannel.AGENT,
                    UsageService.clip(raw, 2000));
            state.addTurnUsage(actualSearchModel, promptTokens, completionTokens, UsageChannel.AGENT);
            return accepted.isEmpty() ? null : accepted;
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.warn("景点联网检索失败（{}），降级为仅知识库推荐", e.getClass().getName());
            AgentTrace sspan = AgentTrace.failure("AttractionSearchAgent", cost,
                    e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            sctx.add(sspan);
            sctx.finish("FAILED");
            usageService.recordAgent(state.getSessionId(), state.getUserId(), state.getUsername(),
                    "ATTRACTIONS", "景点联网检索", "AttractionSearchAgent", null, cost, "FAILED",
                    e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()),
                    searchModel, UsageChannel.RULE_FALLBACK, null);
            return null;
        } finally {
            traceService.finish(sctx);
        }
    }

    /**
     * 酒店通道：知识库无法满足时联网检索真实酒店（enable_search），与知识库酒店名去重。
     * 失败或未找到返回 null（调用方降级为仅知识库推荐，不阻塞主流程）。
     */
    public List<WebHotelCandidate> searchHotelsOnline(TravelState state) {
        if (dashScopeSearchClient == null) {
            log.warn("[Candidate] 联网检索客户端不可用，酒店通道跳过");
            return null;
        }
        long start = System.currentTimeMillis();
        String raw = null;
        // O1：搜索也是一次物理 provider attempt——独立 PROVIDER 叶子 span（与候选重筛 span 同口径）
        TraceContext sctx = traceService.newTrace(state.getSessionId(), "HotelSearchAgent");
        sctx.setSpanId("HotelSearchAgent-" + UUID.randomUUID());
        sctx.setKind("PROVIDER");
        traceService.register(sctx);
        try {
            String city = state.getDestinationName();
            String system = loadPrompt("prompts/search_hotel.txt");
            String user = "城市：" + city
                    + "\n用户的特殊需求：" + state.getExtraRequest()
                    + "\n用户偏好：" + objectMapper.writeValueAsString(state.getPreference());
            DashScopeSearchClient.SearchResult r = dashScopeSearchClient.search(system, user);
            raw = r.content();
            String actualSearchModel = r.model() == null ? searchModel : r.model();
            int promptTokens = r.promptTokens() == null ? 0 : r.promptTokens();
            int completionTokens = r.completionTokens() == null ? 0 : r.completionTokens();
            TokenUsage searchUsage = new TokenUsage(promptTokens, completionTokens);
            JsonNode node = JsonUtils.readTree(raw);
            Set<String> kbNames = hotelMapper.selectList(new LambdaQueryWrapper<Hotel>()
                            .eq(Hotel::getDestinationId, state.getDestinationId())
                            .eq(Hotel::getStatus, 1))
                    .stream().map(Hotel::getName).collect(Collectors.toSet());
            List<WebHotelCandidate> items = new ArrayList<>();
            for (JsonNode it : node.path("items")) {
                String name = it.path("name").asText("").trim();
                if (name.isBlank() || items.size() >= WEB_HOTEL_MAX) {
                    continue;
                }
                if (kbNames.stream().anyMatch(kb -> kb.contains(name) || name.contains(kb))) {
                    continue;
                }
                WebHotelCandidate w = new WebHotelCandidate();
                w.setName(name);
                try {
                    w.setPricePerNight(new BigDecimal(it.path("pricePerNight").asText("").trim()));
                } catch (NumberFormatException ignored) {
                    // 价格缺失：保留 null，由入库校验拒绝（价格未知的酒店不进知识库）
                }
                w.setLevel(it.path("level").asText("").trim());
                w.setTags(it.path("tags").asText("").trim());
                w.setAddress(it.path("address").asText("").trim());
                w.setWhy(it.path("why").asText("").trim());
                items.add(w);
            }
            // 入库前确定性校验 + 审计 + 扩充知识库：只返回通过校验并成功入库的条目
            List<WebHotelCandidate> accepted = ingestWebHotel(state, items, kbNames);
            state.setWebHotelCandidates(accepted);
            long cost = System.currentTimeMillis() - start;
            AgentTrace sspan = AgentTrace.success("HotelSearchAgent", cost, searchUsage);
            sspan.setAnswer(UsageService.clip(raw, 2000));
            sctx.add(sspan);
            sctx.finish("SUCCESS");
            // O1：搜索 token 与耗时如实落库（attempt 明细行），并归集到本轮（turn）
            String remark = "n=" + accepted.size()
                    + (accepted.size() < items.size() ? ",rejected=" + (items.size() - accepted.size()) : "");
            usageService.recordAgent(state.getSessionId(), state.getUserId(), state.getUsername(),
                    "HOTELS", "酒店联网检索", "HotelSearchAgent", searchUsage, cost, "SUCCESS",
                    remark, actualSearchModel, UsageChannel.AGENT,
                    UsageService.clip(raw, 2000));
            state.addTurnUsage(actualSearchModel, promptTokens, completionTokens, UsageChannel.AGENT);
            return accepted.isEmpty() ? null : accepted;
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.warn("酒店联网检索失败（{}），降级为仅知识库推荐", e.getClass().getName());
            AgentTrace sspan = AgentTrace.failure("HotelSearchAgent", cost,
                    e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            sctx.add(sspan);
            sctx.finish("FAILED");
            usageService.recordAgent(state.getSessionId(), state.getUserId(), state.getUsername(),
                    "HOTELS", "酒店联网检索", "HotelSearchAgent", null, cost, "FAILED",
                    e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()),
                    searchModel, UsageChannel.RULE_FALLBACK, null);
            return null;
        } finally {
            traceService.finish(sctx);
        }
    }

    /** 本会话已通过校验入库的网搜景点（不在当前 KB 池内，避免重复并入）；source 双重过滤防异常数据混入 */
    private List<Attraction> webExpandedAttractions(TravelState state, List<Attraction> poolEntities) {
        Set<Long> kbIds = poolEntities.stream().map(Attraction::getId).collect(Collectors.toSet());
        List<Attraction> web = attractionMapper.selectList(new LambdaQueryWrapper<Attraction>()
                .eq(Attraction::getDestinationId, state.getDestinationId())
                .eq(Attraction::getSource, "WEB_SEARCH")
                .eq(Attraction::getSourceRef, state.getSessionId())
                .eq(Attraction::getStatus, 1));
        return web.stream()
                .filter(a -> "WEB_SEARCH".equals(a.getSource())
                        && a.getId() != null && !kbIds.contains(a.getId()))
                .toList();
    }

    /** 本会话已通过校验入库的网搜酒店（不在当前 KB 池内，避免重复并入）；source 双重过滤防异常数据混入 */
    private List<Hotel> webExpandedHotels(TravelState state, List<Hotel> poolEntities) {
        Set<Long> kbIds = poolEntities.stream().map(Hotel::getId).collect(Collectors.toSet());
        List<Hotel> web = hotelMapper.selectList(new LambdaQueryWrapper<Hotel>()
                .eq(Hotel::getDestinationId, state.getDestinationId())
                .eq(Hotel::getSource, "WEB_SEARCH")
                .eq(Hotel::getSourceRef, state.getSessionId())
                .eq(Hotel::getStatus, 1));
        return web.stream()
                .filter(h -> "WEB_SEARCH".equals(h.getSource())
                        && h.getId() != null && !kbIds.contains(h.getId()))
                .toList();
    }

    /**
     * 网搜景点入库：确定性校验 → 审计 → 扩充知识库；只返回通过校验并成功入库的条目。
     * 防恶意写入与美食通道同口径：参数化 insert + WebAttractionValidator 把关 + t_web_food_audit 留痕。
     */
    private List<WebAttractionCandidate> ingestWebAttraction(TravelState state, List<WebAttractionCandidate> items,
                                                             Set<String> kbNames) {
        List<WebAttractionCandidate> accepted = new ArrayList<>();
        Set<String> taken = new LinkedHashSet<>();
        for (WebAttractionCandidate w : items) {
            String norm = normalizeName(w.getName());
            boolean dup = !taken.add(norm) || kbNames.stream().anyMatch(k -> {
                String kn = normalizeName(k);
                return kn.equals(norm) || (kn.length() >= 4 && norm.length() >= 4
                        && (kn.contains(norm) || norm.contains(kn)));
            });
            List<String> reasons = new ArrayList<>(WebAttractionValidator.validate(w));
            if (!LocationConstraintSupport.matches(state.getLocationConstraint(),
                    LocationConstraintSupport.ATTRACTION, w.getName(), w.getAddress(), w.getLng(), w.getLat())) {
                reasons.add(w.getLng() == null || w.getLat() == null
                        ? "LOCATION_UNVERIFIABLE" : "LOCATION_OUT_OF_RANGE");
            }
            if (!dup) {
                // 与已入库的网搜景点再查重（同目的地同来源同名）
                Long same = attractionMapper.selectCount(new LambdaQueryWrapper<Attraction>()
                        .eq(Attraction::getDestinationId, state.getDestinationId())
                        .eq(Attraction::getSource, "WEB_SEARCH")
                        .eq(Attraction::getName, w.getName()));
                if (same != null && same > 0) {
                    dup = true;
                    reasons = List.of("DUPLICATE_DB");
                }
            }
            if (dup && reasons.isEmpty()) {
                reasons = List.of("DUPLICATE");
            }
            if (!reasons.isEmpty()) {
                auditWebPoi(state, "ATTRACTION", null, w.getName(), w.getCategory(), w.getTicketPrice(),
                        w.getAddress(), "REJECT", String.join(",", reasons), w.getWhy());
                continue;
            }
            Attraction a = new Attraction();
            a.setDestinationId(state.getDestinationId());
            a.setName(w.getName().trim());
            a.setCategory(w.getCategory().trim());
            a.setFeatures(w.getTags().trim());
            a.setTags(w.getTags().trim());
            a.setIntensity(3);
            a.setSuggestHours(2.0);
            a.setTicketPrice(w.getTicketPrice());
            a.setRating(4.5);
            a.setIndoor(0);
            a.setStatus(1);
            a.setAddress(blankToNull(w.getAddress()));
            a.setLng(w.getLng());
            a.setLat(w.getLat());
            a.setSource("WEB_SEARCH");
            a.setSourceRef(state.getSessionId());
            a.setSourceNote(UsageService.clip(w.getWhy(), 200));
            try {
                if (attractionMapper.insert(a) > 0) {
                    accepted.add(w);
                    auditWebPoi(state, "ATTRACTION", a.getId(), w.getName(), w.getCategory(), w.getTicketPrice(),
                            w.getAddress(), "ACCEPT", null, w.getWhy());
                } else {
                    auditWebPoi(state, "ATTRACTION", null, w.getName(), w.getCategory(), w.getTicketPrice(),
                            w.getAddress(), "REJECT", "INSERT_FAILED", w.getWhy());
                }
            } catch (Exception e) {
                log.warn("网搜景点入库失败（{}）：{}", w.getName(), e.getClass().getSimpleName());
                auditWebPoi(state, "ATTRACTION", null, w.getName(), w.getCategory(), w.getTicketPrice(),
                        w.getAddress(), "REJECT", "INSERT_ERROR", w.getWhy());
            }
        }
        return accepted;
    }

    /**
     * 网搜酒店入库：确定性校验 → 审计 → 扩充知识库；只返回通过校验并成功入库的条目。
     * 防恶意写入与美食通道同口径：参数化 insert + WebHotelValidator 把关 + t_web_food_audit 留痕。
     */
    private List<WebHotelCandidate> ingestWebHotel(TravelState state, List<WebHotelCandidate> items,
                                                   Set<String> kbNames) {
        List<WebHotelCandidate> accepted = new ArrayList<>();
        Set<String> taken = new LinkedHashSet<>();
        for (WebHotelCandidate w : items) {
            String norm = normalizeName(w.getName());
            boolean dup = !taken.add(norm) || kbNames.stream().anyMatch(k -> {
                String kn = normalizeName(k);
                return kn.equals(norm) || (kn.length() >= 4 && norm.length() >= 4
                        && (kn.contains(norm) || norm.contains(kn)));
            });
            List<String> reasons = WebHotelValidator.validate(w);
            if (!dup) {
                // 与已入库的网搜酒店再查重（同目的地同来源同名）
                Long same = hotelMapper.selectCount(new LambdaQueryWrapper<Hotel>()
                        .eq(Hotel::getDestinationId, state.getDestinationId())
                        .eq(Hotel::getSource, "WEB_SEARCH")
                        .eq(Hotel::getName, w.getName()));
                if (same != null && same > 0) {
                    dup = true;
                    reasons = List.of("DUPLICATE_DB");
                }
            }
            if (dup && reasons.isEmpty()) {
                reasons = List.of("DUPLICATE");
            }
            if (!reasons.isEmpty()) {
                auditWebPoi(state, "HOTEL", null, w.getName(), w.getLevel(), w.getPricePerNight(),
                        w.getAddress(), "REJECT", String.join(",", reasons), w.getWhy());
                continue;
            }
            Hotel h = new Hotel();
            h.setDestinationId(state.getDestinationId());
            h.setName(w.getName().trim());
            h.setPricePerNight(w.getPricePerNight());
            h.setRating(4.5);
            h.setLevel(blankToNull(w.getLevel()) == null ? "舒适" : w.getLevel().trim());
            h.setFeatures(w.getTags().trim());
            h.setTags(w.getTags().trim());
            h.setStatus(1);
            h.setAddress(blankToNull(w.getAddress()));
            h.setSource("WEB_SEARCH");
            h.setSourceRef(state.getSessionId());
            h.setSourceNote(UsageService.clip(w.getWhy(), 200));
            try {
                if (hotelMapper.insert(h) > 0) {
                    accepted.add(w);
                    auditWebPoi(state, "HOTEL", h.getId(), w.getName(), w.getLevel(), w.getPricePerNight(),
                            w.getAddress(), "ACCEPT", null, w.getWhy());
                } else {
                    auditWebPoi(state, "HOTEL", null, w.getName(), w.getLevel(), w.getPricePerNight(),
                            w.getAddress(), "REJECT", "INSERT_FAILED", w.getWhy());
                }
            } catch (Exception e) {
                log.warn("网搜酒店入库失败（{}）：{}", w.getName(), e.getClass().getSimpleName());
                auditWebPoi(state, "HOTEL", null, w.getName(), w.getLevel(), w.getPricePerNight(),
                        w.getAddress(), "REJECT", "INSERT_ERROR", w.getWhy());
            }
        }
        return accepted;
    }

    /** 本会话已通过校验入库的网搜店（不在当前 KB 池内，避免重复并入）；source 双重过滤防异常数据混入 */
    private List<Restaurant> webExpandedRestaurants(TravelState state, List<Restaurant> poolEntities) {
        Set<Long> kbIds = poolEntities.stream().map(Restaurant::getId).collect(Collectors.toSet());
        List<Restaurant> web = restaurantMapper.selectList(new LambdaQueryWrapper<Restaurant>()
                .eq(Restaurant::getDestinationId, state.getDestinationId())
                .eq(Restaurant::getSource, "WEB_SEARCH")
                .eq(Restaurant::getSourceRef, state.getSessionId())
                .eq(Restaurant::getStatus, 1));
        return web.stream()
                .filter(r -> "WEB_SEARCH".equals(r.getSource())
                        && r.getId() != null && !kbIds.contains(r.getId()))
                .toList();
    }

    /**
     * 网搜结果入库：确定性校验 → 审计 → 扩充知识库；只返回通过校验并成功入库的条目。
     * 防恶意写入：SQL 注入由参数化 insert 兜底，内容与语义由 WebFoodValidator 把关，
     * 每条结果（接受/拒绝）都写 t_web_food_audit 供追溯与事后人工复核。
     */
    private List<WebFoodCandidate> ingestWebFood(TravelState state, List<WebFoodCandidate> items,
                                                 Set<String> kbNames) {
        List<WebFoodCandidate> accepted = new ArrayList<>();
        Set<String> taken = new LinkedHashSet<>();
        for (WebFoodCandidate w : items) {
            String norm = normalizeName(w.getName());
            boolean dup = !taken.add(norm) || kbNames.stream().anyMatch(k -> {
                String kn = normalizeName(k);
                return kn.equals(norm) || (kn.length() >= 4 && norm.length() >= 4
                        && (kn.contains(norm) || norm.contains(kn)));
            });
            List<String> reasons = WebFoodValidator.validate(w);
            if (!dup) {
                // 与已入库的网搜店再查重（同目的地同来源同名）
                Long same = restaurantMapper.selectCount(new LambdaQueryWrapper<Restaurant>()
                        .eq(Restaurant::getDestinationId, state.getDestinationId())
                        .eq(Restaurant::getSource, "WEB_SEARCH")
                        .eq(Restaurant::getName, w.getName()));
                if (same != null && same > 0) {
                    dup = true;
                    reasons = List.of("DUPLICATE_DB");
                }
            }
            if (dup && reasons.isEmpty()) {
                reasons = List.of("DUPLICATE");
            }
            if (!reasons.isEmpty()) {
                auditWebPoi(state, "FOOD", null, w.getName(), w.getCuisine(), w.getAvgPrice(),
                        w.getAddress(), "REJECT", String.join(",", reasons), w.getWhy());
                continue;
            }
            Restaurant r = new Restaurant();
            r.setDestinationId(state.getDestinationId());
            r.setName(w.getName().trim());
            r.setCuisine(w.getCuisine().trim());
            r.setAvgPrice(w.getAvgPrice());
            r.setAddress(blankToNull(w.getAddress()));
            r.setRating(4.5);
            r.setStatus(1);
            r.setSource("WEB_SEARCH");
            r.setSourceRef(state.getSessionId());
            r.setSourceNote(UsageService.clip(w.getWhy(), 200));
            try {
                if (restaurantMapper.insert(r) > 0) {
                    accepted.add(w);
                    auditWebPoi(state, "FOOD", r.getId(), w.getName(), w.getCuisine(), w.getAvgPrice(),
                            w.getAddress(), "ACCEPT", null, w.getWhy());
                } else {
                    auditWebPoi(state, "FOOD", null, w.getName(), w.getCuisine(), w.getAvgPrice(),
                            w.getAddress(), "REJECT", "INSERT_FAILED", w.getWhy());
                }
            } catch (Exception e) {
                log.warn("网搜店铺入库失败（{}）：{}", w.getName(), e.getClass().getSimpleName());
                auditWebPoi(state, "FOOD", null, w.getName(), w.getCuisine(), w.getAvgPrice(),
                        w.getAddress(), "REJECT", "INSERT_ERROR", w.getWhy());
            }
        }
        return accepted;
    }

    /**
     * 逐条审计：接受/拒绝都留痕（mapper 未装配时静默跳过，不影响主流程）。
     * placeType 区分 FOOD/ATTRACTION/HOTEL；placeId 为入库后的 POI 主键（ACCEPT 时回填）。
     */
    private void auditWebPoi(TravelState state, String placeType, Long placeId, String name, String secondary,
                             BigDecimal price, String address, String action, String rejectReason, String why) {
        if (webFoodAuditMapper == null) {
            return;
        }
        try {
            WebFoodAudit a = new WebFoodAudit();
            a.setSessionId(state.getSessionId());
            a.setDestinationId(state.getDestinationId());
            a.setUserId(state.getUserId());
            a.setPlaceType(placeType);
            a.setPlaceId(placeId);
            a.setName(UsageService.clip(name, 128));
            a.setCuisine(UsageService.clip(secondary, 32));
            a.setAvgPrice(price);
            a.setAddress(UsageService.clip(address, 128));
            a.setAction(action);
            a.setRejectReason(UsageService.clip(rejectReason, 255));
            a.setRawPayload(UsageService.clip("name=" + name + "; why=" + why, 500));
            webFoodAuditMapper.insert(a);
        } catch (Exception e) {
            log.warn("网搜审计写入失败：{}", e.getClass().getSimpleName());
        }
    }

    private static String normalizeName(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[\\s（）()·\\-—]+", "").toLowerCase();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String loadPrompt(String path) throws java.io.IOException {
        org.springframework.core.io.Resource res = new org.springframework.core.io.ClassPathResource(path);
        try (java.io.InputStream in = res.getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** 换一批：从备选池翻下一页（已勾选置顶、不重复）；返回 0=新页 1=翻完一轮从头再来 */
    public int nextFoodBatch(TravelState state) {
        List<FoodCandidate> pool = state.getFoodPool() == null ? List.of() : state.getFoodPool();
        Set<Long> picked = state.getPickedFoodIds();

        Map<String, List<FoodCandidate.FoodItem>> batchMap = new LinkedHashMap<>();
        int pickedCount = 0;
        List<FoodCandidate.FoodItem> unpicked = new ArrayList<>();
        List<String> unpickedLabel = new ArrayList<>();
        for (FoodCandidate g : pool) {
            for (FoodCandidate.FoodItem it : g.getRestaurants()) {
                String label = it.getMealType() != null ? it.getMealType() : g.getCuisine();
                if (picked.contains(it.getRestaurantId())) {
                    batchMap.computeIfAbsent(label, k -> new ArrayList<>()).add(it);
                    pickedCount++;
                } else {
                    unpicked.add(it);
                    unpickedLabel.add(label);
                }
            }
        }

        int cursor = state.getFoodCursor();
        int wrapped = 0;
        if (cursor >= unpicked.size()) {
            cursor = 0;
            wrapped = 1;
        }
        int need = Math.max(0, FOOD_BATCH - pickedCount);
        int take = Math.min(need, unpicked.size() - cursor);
        for (int i = 0; i < take; i++) {
            batchMap.computeIfAbsent(unpickedLabel.get(cursor + i), k -> new ArrayList<>())
                    .add(unpicked.get(cursor + i));
        }
        state.setFoodCursor(cursor + take);
        state.setFoodCandidates(batchMap.entrySet().stream().map(e -> {
            FoodCandidate c = new FoodCandidate();
            c.setCuisine(e.getKey());
            c.setRestaurants(e.getValue());
            return c;
        }).toList());
        return wrapped;
    }

    /** 字段级美食人均上限（foodMaxPricePerPerson 约束，多条取最严）；无则 null */
    private static BigDecimal foodPriceCap(TravelState state) {
        RequirementSnapshot snap = state.getRequirementSnapshot();
        if (snap == null || snap.getConstraints() == null) {
            return null;
        }
        BigDecimal cap = null;
        for (ConstraintEntry c : snap.getConstraints()) {
            if (!"foodMaxPricePerPerson".equals(c.getKey()) || !"ACTIVE".equals(c.getStatus())
                    || c.getValue() == null) {
                continue;
            }
            try {
                BigDecimal v = new BigDecimal(c.getValue().trim());
                if (v.signum() > 0 && (cap == null || v.compareTo(cap) < 0)) {
                    cap = v;
                }
            } catch (NumberFormatException ignored) {
                // 非法价格口径：不启用该条件，避免误杀
            }
        }
        return cap;
    }

    /** 口味档位：当前口味偏好对应的风味次序（0 最优先，不在列表内为列表长度）；未指定口味按默认偏好 */
    private static List<String> preferredCuisines(TravelPreference p) {
        return switch (p == null || p.getFoodTaste() == null ? "" : p.getFoodTaste()) {
            case "辣" -> List.of("火锅", "川菜");
            case "清淡" -> List.of("素斋", "小吃", "本地菜");
            default -> List.of("本地菜", "小吃");
        };
    }

    /** 用户明确的平价诉求（额外要求或特殊需求中出现即生效，会话内持续有效） */
    private static final List<String> PRICE_SENSITIVE_WORDS = List.of(
            "不要贵", "不要太贵", "别太贵", "不贵", "太贵", "贵了", "便宜", "平价", "实惠", "省钱", "经济实惠");

    /** 用户明确想吃小吃的措辞（不包含「不要小吃」等否定表述） */
    private static final List<String> SNACK_WANT_WORDS = List.of(
            "特色小吃", "想吃小吃", "要小吃", "来点小吃", "点小吃", "尝尝小吃");

    private static boolean priceSensitive(TravelState state) {
        return containsAny(channelRequest(state, CandidateChannelCoordinator.CHANNEL_FOOD), PRICE_SENSITIVE_WORDS)
                || containsAny(state.getPreference() == null ? null
                        : state.getPreference().getSpecialRequests(), PRICE_SENSITIVE_WORDS);
    }

    private static boolean wantsSnacks(TravelState state) {
        return containsAny(channelRequest(state, CandidateChannelCoordinator.CHANNEL_FOOD), SNACK_WANT_WORDS)
                || containsAny(state.getPreference() == null ? null
                        : state.getPreference().getSpecialRequests(), SNACK_WANT_WORDS);
    }

    private static boolean containsAny(String text, List<String> words) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** 「想吃小吃」时小吃档位提前，其余顺序不变 */
    private static List<String> snackFirstTiers(List<String> tiers) {
        List<String> out = new ArrayList<>(tiers);
        out.remove("小吃");
        out.add(0, "小吃");
        return out;
    }

    /** 标签评估结果：命中数（入池优先级）、评分加成、明细说明 */
    private record TagEval(Map<Long, Integer> hits, Map<Long, Double> bonus, Map<Long, String> notes) {
    }

    /**
     * 通用标签评估：needTags × 实体标签（结构化标签优先，缺失回退文本子串）；
     * 命中加分 min(命中×0.4, 1.2)、冲突减分 0.6/对；免费类需求且门票为 0 额外计一次命中。
     */
    private static TagEval evaluateTags(List<String> needTags, List<Long> ids,
                                        java.util.function.Function<Long, String> tagsOf,
                                        java.util.function.Function<Long, String> textOf,
                                        java.util.function.Function<Long, Boolean> freeOf) {
        Map<Long, Integer> hits = new LinkedHashMap<>();
        Map<Long, Double> bonus = new LinkedHashMap<>();
        Map<Long, String> notes = new LinkedHashMap<>();
        if (needTags.isEmpty()) {
            return new TagEval(hits, bonus, notes);
        }
        Set<String> need = new LinkedHashSet<>(needTags);
        boolean freeTag = need.contains("免费") || need.contains("低价");
        for (Long id : ids) {
            String tags = tagsOf.apply(id);
            TagMatcher.Verdict v;
            Set<String> hitT = new LinkedHashSet<>();
            Set<String> confT = new LinkedHashSet<>();
            if (tags != null && !tags.isBlank()) {
                Set<String> et = TagMatcher.parse(tags);
                v = TagMatcher.evaluate(need, et);
                hitT = TagMatcher.hitTags(need, et);
                confT = TagMatcher.conflictTags(need, et);
            } else {
                v = TagMatcher.evaluateByText(needTags, textOf.apply(id));
            }
            boolean freeHit = freeTag && Boolean.TRUE.equals(freeOf.apply(id));
            int h = v.hits() + (freeHit ? 1 : 0);
            double b = Math.min(h * TagMatcher.HIT_BONUS, TagMatcher.HIT_CAP)
                    - v.conflicts() * TagMatcher.CONFLICT_PENALTY;
            if (h > 0) {
                hits.put(id, Math.min(h, 3));
            }
            if (h > 0 || v.conflicts() > 0) {
                bonus.put(id, b);
                String note = v.note(hitT, confT);
                if (freeHit) {
                    note = (note.isEmpty() ? "" : note + " · ") + "免费";
                }
                notes.put(id, note);
            }
        }
        return new TagEval(hits, bonus, notes);
    }

    private List<Restaurant> orderRestaurants(List<Restaurant> all, TravelPreference p) {
        List<String> preferred = preferredCuisines(p);
        Comparator<Restaurant> cmp = Comparator.comparingInt((Restaurant r) -> {
            int i = preferred.indexOf(r.getCuisine());
            return i < 0 ? preferred.size() : i;
        }).thenComparing(Restaurant::getRating, Comparator.reverseOrder());
        return all.stream().sorted(cmp).toList();
    }

    private Map<String, Object> restaurantPoolItem(TravelState state, Restaurant r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("name", r.getName());
        m.put("cuisine", r.getCuisine());
        m.put("avgPrice", r.getAvgPrice());
        m.put("signatureDish", r.getSignatureDish() == null ? "" : r.getSignatureDish());
        m.put("address", r.getAddress() == null ? "" : r.getAddress());
        m.put("rating", r.getRating());
        m.put("distanceToAnchorKm",
                locationDistance(state, LocationConstraintSupport.FOOD, r.getLng(), r.getLat()));
        return m;
    }

    private FoodCandidate.FoodItem foodItemOf(TravelState state, Restaurant r, Map<Long, String> mealTypes) {
        FoodCandidate.FoodItem item = new FoodCandidate.FoodItem();
        item.setRestaurantId(r.getId());
        item.setName(r.getName());
        item.setAvgPrice(r.getAvgPrice());
        item.setSignatureDish(r.getSignatureDish());
        item.setTags(r.getTags());
        item.setSource(r.getSource());
        if ("WEB_SEARCH".equals(r.getSource()) && r.getSourceNote() != null) {
            item.setReason(r.getSourceNote());
        }
        if (mealTypes != null) {
            item.setMealType(mealTypes.get(r.getId()));
        }
        item.setDistanceToAnchor(
                locationDistance(state, LocationConstraintSupport.FOOD, r.getLng(), r.getLat()));
        return item;
    }

    /** 规划天数（未确认按 2 天口径，与旧行为一致） */
    private int daysOf(TravelState state) {
        return state.getPreference().getDays() == null ? 2 : state.getPreference().getDays();
    }

    /**
     * 规则侧餐次归类（知识库无店铺时段数据，属诚实近似）：
     * 小吃风味 → 小吃；其余按午饭/晚饭配额轮转；早餐类店铺无法可靠识别，留给 AI 按店名补充。
     */
    private void assignMealTypes(List<Restaurant> pool, ResolvedPlanningPolicy planning, int days,
                                 boolean excludeSnacks, Map<Long, String> out) {
        int lunchTotal = mealEventCount(planning.getMeal().getLunch(), days);
        int dinnerTotal = mealEventCount(planning.getMeal().getDinner(), days);
        int lunch = 0;
        int dinner = 0;
        for (Restaurant r : pool) {
            if (MealPolicySupport.isSnack(r)) {
                if (!excludeSnacks) {
                    out.put(r.getId(), "小吃");
                }
                continue;
            }
            if (lunch < lunchTotal) {
                out.put(r.getId(), "午餐");
                lunch++;
            } else if (dinner < dinnerTotal) {
                out.put(r.getId(), "晚餐");
                dinner++;
            } else {
                // 超出餐次配额的店铺仍归正餐展示（候选浏览用，最终排程由排程器决定）
                out.put(r.getId(), "午餐");
            }
        }
    }

    private static int mealEventCount(ResolvedPlanningPolicy.MealRule rule, int days) {
        if (rule == null) {
            return 0;
        }
        return rule.getScope() == RequirementScope.PER_DAY ? rule.getCount() * days : rule.getCount();
    }

    private static boolean isExplicitMeal(ResolvedPlanningPolicy.MealRule rule) {
        return rule != null && rule.isExplicit();
    }

    /** AI 输出餐次归一：仅接受四类，其余视为未标注 */
    private String normalizeMealType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim()) {
            case "早餐", "早饭" -> "早餐";
            case "午餐", "午饭", "中饭" -> "午餐";
            case "晚餐", "晚饭" -> "晚餐";
            case "小吃", "夜宵" -> "小吃";
            default -> null;
        };
    }

    /** 店铺列表 → 按风味分组的候选组（保留完整信息：人均/招牌菜/氛围与餐次归类） */
    private List<FoodCandidate> groupItems(TravelState state, List<Restaurant> rs, Map<Long, String> mealTypes) {
        Map<String, List<FoodCandidate.FoodItem>> m = new LinkedHashMap<>();
        for (Restaurant r : rs) {
            m.computeIfAbsent(r.getCuisine(), k -> new ArrayList<>()).add(foodItemOf(state, r, mealTypes));
        }
        List<FoodCandidate> out = new ArrayList<>();
        for (Map.Entry<String, List<FoodCandidate.FoodItem>> e : m.entrySet()) {
            FoodCandidate c = new FoodCandidate();
            c.setCuisine(e.getKey());
            c.setRestaurants(e.getValue());
            out.add(c);
        }
        return out;
    }

    // ==================== 酒店候选 ====================

    /** 生成酒店备选池（按距已选点中心距离排序，规则秒出；有额外要求时调 AI 重筛）；返回 AI 是否失败 */
    public boolean generateHotels(TravelState state) {
        List<Hotel> hotels = hotelMapper.selectList(new LambdaQueryWrapper<Hotel>()
                .eq(Hotel::getDestinationId, state.getDestinationId())
                .eq(Hotel::getStatus, 1));
        double[] center = centerOf(state);
        HardConstraintEvaluator.HardPolicy policy = buildHardPolicy(state);
        String city = state.getDestinationName();

        // S07：距离排序后逐项硬过滤（禁止截断后过滤）；网搜酒店无坐标排到末尾
        List<Hotel> sorted = hotels.stream()
                .sorted(Comparator.comparingDouble(h -> distanceKmSafe(center, h.getLng(), h.getLat()) == null
                        ? Double.MAX_VALUE : distanceKmSafe(center, h.getLng(), h.getLat())))
                .toList();
        List<Hotel> poolEntities = new ArrayList<>();
        Map<String, Object> evidence = new LinkedHashMap<>();
        Map<String, List<String>> acceptedByOrigin = new LinkedHashMap<>();
        int scanned = 0;
        int unknown = 0;
        int locationRejected = 0;
        for (Hotel h : sorted) {
            scanned++;
            if (!locationEligible(state, LocationConstraintSupport.HOTEL,
                    h.getName(), h.getAddress(), h.getLng(), h.getLat())) {
                locationRejected++;
                continue;
            }
            HardConstraintEvaluator.Verdict v = HardConstraintEvaluator.evaluate(hotelFact(h, city), policy);
            if (v.status() == HardConstraintEvaluator.Status.ELIGIBLE) {
                poolEntities.add(h);
                if (poolEntities.size() >= HOTEL_POOL_MAX) {
                    break;
                }
            } else if (v.status() == HardConstraintEvaluator.Status.UNKNOWN) {
                unknown++;
            }
        }
        acceptedByOrigin.put("SQL", poolEntities.stream()
                .map(h -> placeKey("HOTEL", h.getId())).toList());
        List<HotelCandidate> pool = poolEntities.stream()
                .map(h -> hotelOf(state, h, center, "综合推荐")).collect(Collectors.toCollection(ArrayList::new));

        boolean aiOk = true;
        List<Hotel> finalEntities = poolEntities;
        String req = channelRequest(state, CandidateChannelCoordinator.CHANNEL_HOTEL);
        if (req != null && !req.isBlank()) {
            List<Hotel> refined = refineHotelsByAi(state, poolEntities, pool, center, req);
            if (refined != null) {
                boolean suppressed = false;
                if (refined.isEmpty()) {
                    // AI 表示池内无匹配：保留规则池兜底展示，同时自动联网检索补候选
                    evidence.put("aiNoMatch", true);
                    suppressed = triggerWebSearchForHotels(state);
                } else if (refined.size() < HOTEL_POOL_FLOOR) {
                    // 确定性触发：AI 精挑数量低于下限即视为覆盖不足，直接联网检索扩充
                    evidence.put("aiInsufficient", true);
                    suppressed = triggerWebSearchForHotels(state);
                }
                // AI 精挑通常只有 3 家：先并入本会话已通过校验入库的网搜酒店（与 KB 酒店同等对待），
                // 仍不足下限时用规则池补足（补足项同样已经过硬过滤）
                finalEntities = new ArrayList<>(refined);
                Set<Long> chosenIds = refined.stream().map(Hotel::getId).collect(Collectors.toSet());
                List<String> aiAccepted = refined.stream().map(h -> placeKey("HOTEL", h.getId())).toList();
                List<String> webAccepted = new ArrayList<>();
                List<String> backfillAccepted = new ArrayList<>();
                List<HotelCandidate> extraPool = new ArrayList<>();
                // 网搜补充池：本会话新搜结果在前；跨会话复用（历史验证过的网搜酒店）在后（功能隔离：未装配时只有前者）
                List<Hotel> webPool = new ArrayList<>(webExpandedHotels(state, poolEntities));
                Set<Long> webPoolIds = webPool.stream().map(Hotel::getId).collect(Collectors.toSet());
                if (promotion != null) {
                    for (Hotel w : promotion.reusableWebHotels(state.getDestinationId(),
                            java.time.LocalDateTime.now())) {
                        if (webPoolIds.add(w.getId())) {
                            webPool.add(w);
                        }
                    }
                }
                for (Hotel w : webPool) {
                    if (finalEntities.size() >= HOTEL_POOL_FLOOR) {
                        break;
                    }
                    if (!locationEligible(state, LocationConstraintSupport.HOTEL,
                            w.getName(), w.getAddress(), w.getLng(), w.getLat())) {
                        locationRejected++;
                        continue;
                    }
                    if (chosenIds.add(w.getId())) {
                        finalEntities.add(w);
                        extraPool.add(hotelOf(state, w, center, "联网检索补充"));
                        webAccepted.add(placeKey("HOTEL", w.getId()));
                        if (promotion != null) {
                            // 晋升计数是旁路功能：任何异常都不阻断候选主流程
                            try {
                                promotion.recordRecommend(FoodPromotionService.TYPE_HOTEL,
                                        state.getSessionId(), w.getId(), state.getDestinationId());
                            } catch (Exception e) {
                                log.warn("网搜酒店推荐计数失败（{}），已降级跳过", e.getClass().getSimpleName());
                            }
                        }
                    }
                }
                if (!webAccepted.isEmpty()) {
                    String adv = state.getCandidateAdvice();
                    state.setCandidateAdvice((adv == null || adv.isBlank() ? "" : adv.trim() + " ")
                            + (suppressed
                                ? "已复用历史网搜结果补充 " + webAccepted.size() + " 家符合要求的酒店。"
                                : "已联网检索扩充 " + webAccepted.size() + " 家符合要求的酒店。"));
                } else if (Boolean.TRUE.equals(evidence.get("aiInsufficient"))
                        || Boolean.TRUE.equals(evidence.get("aiNoMatch"))) {
                    // 触发过检索但没有新条目并入（失败或全部被校验拒绝）：保持既有文案；
                    // 仅当检索被「历史复用抑制」跳过时使用新文案，两种场景都如实告知
                    String adv = state.getCandidateAdvice();
                    state.setCandidateAdvice((adv == null || adv.isBlank() ? "" : adv.trim() + " ")
                            + (suppressed
                                ? "（本次未获取到新的联网结果，已按备选池给出结果，可稍后重试）"
                                : "（联网检索暂不可用，本次按备选池给出结果，可稍后重试）"));
                }
                for (Hotel h : poolEntities) {
                    if (finalEntities.size() >= HOTEL_POOL_FLOOR) {
                        break;
                    }
                    if (chosenIds.add(h.getId())) {
                        finalEntities.add(h);
                        extraPool.add(hotelOf(state, h, center, "综合推荐"));
                        backfillAccepted.add(placeKey("HOTEL", h.getId()));
                    }
                }
                acceptedByOrigin.put("AI", aiAccepted);
                acceptedByOrigin.put("WEB", webAccepted);
                acceptedByOrigin.put("BACKFILL", backfillAccepted);
                // 以最终实体重建展示池（保留 AI 精选项的 why 文案；无坐标网搜酒店距离为 null）
                Map<Long, HotelCandidate> whyById = new LinkedHashMap<>();
                pool.forEach(c -> whyById.putIfAbsent(c.getHotelId(), c));
                extraPool.forEach(c -> whyById.putIfAbsent(c.getHotelId(), c));
                pool.clear();
                for (Hotel h : finalEntities) {
                    pool.add(whyById.getOrDefault(h.getId(), hotelOf(state, h, center, "综合推荐")));
                }
            } else {
                aiOk = false;
                acceptedByOrigin.put("BACKFILL", acceptedByOrigin.get("SQL"));
            }
        } else {
            state.setCandidateAdvice(null);
        }
        // AHP 基础分 + 标签加成（命中加分/冲突减分）= 展示综合分
        Map<String, Double> w = weightsOf(state);
        Map<Long, Double> scores = CandidateScorer.scoreHotels(finalEntities, center, state.getPreference(), w);
        List<String> needTags = state.getNeedTags() == null ? List.of() : state.getNeedTags();
        Map<Long, Hotel> hotById = finalEntities.stream()
                .collect(Collectors.toMap(Hotel::getId, h -> h));
        TagEval hotelTagEval = evaluateTags(needTags, finalEntities.stream().map(Hotel::getId).toList(),
                id -> hotById.get(id) == null ? null : hotById.get(id).getTags(),
                id -> {
                    Hotel h = hotById.get(id);
                    return h == null ? "" : h.getName() + h.getLevel()
                            + (h.getFeatures() == null ? "" : h.getFeatures());
                },
                id -> false);
        for (HotelCandidate c : pool) {
            double bonus = hotelTagEval.bonus().getOrDefault(c.getHotelId(), 0.0);
            c.setScore(round(scores.getOrDefault(c.getHotelId(), 0.0) + bonus
                    + locationBonus(state, LocationConstraintSupport.HOTEL, c.getDistanceToAnchor())));
            String note = hotelTagEval.notes().get(c.getHotelId());
            if (note != null && !note.isBlank()) {
                c.setTagNote(note);
            }
        }
        pool.sort(Comparator.comparingDouble(HotelCandidate::getScore).reversed());
        state.setHotelPool(pool);
        state.setHotelCursor(0);
        // S07：锁定项与推荐池分离——池重建不通过 retainAll 清空已锁定地点
        evidence.put("scannedCount", scanned);
        evidence.put("eligibleCount", finalEntities.size());
        evidence.put("unknownCount", unknown);
        evidence.put("locationRejectedCount", locationRejected);
        evidence.put("acceptedByOrigin", acceptedByOrigin);
        sanitizeCandidateAdvice(state, "酒店");
        newSnapshot(state, "HOTEL",
                finalEntities.stream().map(h -> placeKey("HOTEL", h.getId())).toList(), evidence);
        obsRecall(null, state, "HOTEL", evidence);
        nextHotelBatch(state);
        state.rememberCandidateAdvice(CandidateChannelCoordinator.CHANNEL_HOTEL);
        log.info("[Candidate] 酒店备选池 {} 个（会话 {}，AI失败={}）", pool.size(), state.getSessionId(), !aiOk);
        return aiOk;
    }

    /**
     * 将模型选择结果解释成可验证的推荐方法，不直接展示模型自由生成的地点名。
     * 具体地点由 acceptedByOrigin.AI 生成结构化可点击引用；联网结果说明只保留 Java 生成的可验证后缀。
     */
    private static void sanitizeCandidateAdvice(TravelState state, String label) {
        String raw = state.getCandidateAdvice();
        String scope = adviceScope(label);
        if ((raw == null || raw.isBlank())
                && !LocationConstraintSupport.appliesTo(state.getLocationConstraint(), scope)) {
            return;
        }
        state.setCandidateAdvice(verifiedCandidateAdvice(state, label, raw == null ? "" : raw));
    }

    /** 将旧会话中的固定模板文案也可即时升级为新的解释文案。 */
    public static String verifiedCandidateAdvice(TravelState state, String label, String raw) {
        List<String> allTags = state.getNeedTags() == null ? List.of() : state.getNeedTags().stream()
                .filter(t -> t != null && !t.isBlank()).distinct().toList();
        List<String> channelWords = switch (label) {
            case "景点" -> List.of("景", "夜", "拍照", "打卡", "游船", "情侣", "浪漫", "文化", "户外");
            case "餐厅" -> List.of("菜", "餐", "吃", "口味", "辣", "清淡", "小吃", "情侣", "浪漫", "氛围", "新街口");
            case "酒店" -> List.of("酒店", "住宿", "安静", "地铁", "交通", "位置", "预算", "泳池", "亲子");
            default -> List.of();
        };
        List<String> tags = allTags.stream()
                .filter(t -> channelWords.stream().anyMatch(t::contains)).limit(5).toList();
        if (tags.isEmpty()) {
            tags = allTags.stream().limit(5).toList();
        }
        String focus = tags.isEmpty() ? "你的特殊要求" : "「" + String.join("、", tags) + "」等需求";
        String criteria = switch (label) {
            case "景点" -> "景点类型、游玩强度、门票成本和行程动线";
            case "餐厅" -> "口味、人均消费、所在位置和餐次适配";
            case "酒店" -> "住宿预算、评分、距离和设施特色";
            default -> "需求匹配、预算和位置";
        };
        LocationConstraint location = state.getLocationConstraint();
        String scope = adviceScope(label);
        StringBuilder safe = new StringBuilder();
        if (LocationConstraintSupport.appliesTo(location, scope)) {
            String locationSummary = LocationConstraintSupport.summary(location);
            if (LocationConstraint.RESOLVED.equals(location.getStatus())) {
                safe.append("本轮先按").append(locationSummary).append("确定性过滤，再");
            } else {
                safe.append(locationSummary).append("，暂不将距离作为已满足条件；本轮");
            }
        } else {
            safe.append("本轮");
        }
        safe.append("优先匹配").append(focus)
                .append("，并综合考虑").append(criteria)
                .append("。下方候选由 AI 从当前备选池选出，候选卡片中的推荐理由说明具体匹配点。");
        Matcher matcher = VERIFIED_ADVICE_SUFFIX.matcher(raw);
        while (matcher.find()) {
            safe.append(' ').append(matcher.group(1));
        }
        return safe.toString();
    }

    private static String adviceScope(String label) {
        return switch (label) {
            case "景点" -> LocationConstraintSupport.ATTRACTION;
            case "餐厅" -> LocationConstraintSupport.FOOD;
            case "酒店" -> LocationConstraintSupport.HOTEL;
            default -> "";
        };
    }

    private List<Hotel> refineHotelsByAi(TravelState state, List<Hotel> poolEntities,
                                         List<HotelCandidate> pool, double[] center, String request) {
        String raw = null;
        ContextObservation observation = ContextObservation.disabled();
        try {
            String poolJson = objectMapper.writeValueAsString(
                    poolEntities.stream().map(h -> hotelPoolItem(state, h, center)).toList());
            String prefJson = prefJson(state, request);
            AgentContextInvocation invocation = agentContextRuntime == null
                    || !agentContextRuntime.isActive(AgentContextRole.HOTEL) ? null
                    : agentContextRuntime.candidate(state, AgentContextRole.HOTEL,
                    poolJson, prefJson, 0);
            if (invocation != null) {
                poolJson = invocation.argument("pool");
                prefJson = invocation.argument("preference");
            }
            observation = beginCandidateContext(state, "HotelAgent", "candidate-hotel-v1",
                    poolJson, prefJson, invocation);
            final String poolArg = poolJson;
            final String prefArg = prefJson;
            Traced<String> traced = withTrace(state, "HotelAgent", "酒店AI重筛",
                    observation,
                    () -> hotelAgent.select(poolArg, prefArg));
            raw = traced.content();
            AgentTrace span = traced.span();
            JsonNode node = JsonUtils.readTree(raw);
            // S12：解析结果分层记录
            span.setParseStatus(AgentTrace.SUCCESS);
            ArrayNode itemsNode = AgentOutputParser.extractItems(
                    node, AgentOutputParser::hotelItem, "hotelId", "id");
            state.setCandidateAdvice(AgentOutputParser.adviceOf(node));
            Map<Long, Hotel> byId = poolEntities.stream()
                    .collect(Collectors.toMap(Hotel::getId, h -> h));
            List<HotelCandidate> refined = new ArrayList<>();
            List<Hotel> refinedEntities = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            HardConstraintEvaluator.HardPolicy policy = buildHardPolicy(state);
            String city = state.getDestinationName();
            for (HotelCandidate c : JsonUtils.parseList(itemsNode.toString(), HotelCandidate.class)) {
                Hotel h = byId.get(c.getHotelId());
                if (h == null || !seen.add(h.getId())) {
                    continue;
                }
                // S07：AI 出口同样必须过统一硬过滤——模型标签不能替代官方事实
                HardConstraintEvaluator.Verdict v =
                        HardConstraintEvaluator.evaluate(hotelFact(h, city), policy);
                if (v.status() != HardConstraintEvaluator.Status.ELIGIBLE) {
                    log.warn("酒店候选 AI 输出项 {} 未通过硬过滤（{}），已剔除", h.getId(), v.reasonCode());
                    continue;
                }
                refined.add(hotelOf(state, h, center,
                        c.getWhy() == null || c.getWhy().isBlank() ? "AI 推荐" : c.getWhy()));
                refinedEntities.add(h);
                if (refined.size() >= LLM_SELECT_MAX) {
                    break;
                }
            }
            if (refined.isEmpty() && itemsNode.size() > 0) {
                // S12：模型成功但语义失败——validation=FAILED + 回退原因 + 业务结果 DEGRADED
                span.setValidationStatus(AgentTrace.FAILED);
                span.setFallbackReason("HARD_CONSTRAINT_VIOLATION");
                traced.ctx().setBusinessStatus("DEGRADED");
                contextBaselineRecorder.parseFinished(observation, "SUCCESS", "FAILED",
                        "HARD_CONSTRAINT_VIOLATION");
                log.warn("酒店候选 AI 输出解析后有效项为 0（输出形状或 id 与池不符），原始输出前300字：{}", snippet(raw));
                return null;
            }
            if (refined.isEmpty()) {
                // AI 明确表示池内无匹配：合法语义结果而非失败——返回空列表，
                // 调用方保留规则池兜底展示并触发联网检索补候选（与美食/景点通道同口径）
                span.setValidationStatus(AgentTrace.SUCCESS);
                span.setFallbackReason("POOL_NO_MATCH");
                traced.ctx().setBusinessStatus("DEGRADED");
                contextBaselineRecorder.parseFinished(observation, "SUCCESS", "SUCCESS", "POOL_NO_MATCH");
                log.warn("酒店候选 AI 表示池内无匹配（items 为空），将保留规则池并尝试联网检索。原始输出前300字：{}", snippet(raw));
                return new ArrayList<>();
            }
            span.setValidationStatus(AgentTrace.SUCCESS);
            traced.ctx().setBusinessStatus("COMMITTED");
            pool.clear();
            pool.addAll(refined);
            contextBaselineRecorder.parseFinished(observation, "SUCCESS", "SUCCESS", null);
            return refinedEntities;
        } catch (Exception e) {
            contextBaselineRecorder.parseFinished(observation, "FAILED", "UNKNOWN",
                    e.getClass().getSimpleName());
            state.setCandidateAdvice(null);
            log.warn("酒店候选 AI 重筛失败（{}），沿用知识库规则池", e.getClass().getName(), e);
            log.warn("  HotelAgent 原始输出前300字：{}", snippet(raw));
            return null;
        }
    }

    private ContextObservation beginCandidateContext(TravelState state, String agent,
                                                      String schemaVersion, String poolJson,
                                                      String preferenceJson,
                                                      AgentContextInvocation invocation) {
        int requirementRevision = state.getRequirementSnapshot() == null
                ? 0 : state.getRequirementSnapshot().getRevision();
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put(ContextSectionNames.CANDIDATE_POOL, poolJson);
        sections.put(ContextSectionNames.PREFERENCE, preferenceJson);
        if (invocation != null) sections.put("AGENT_CONTEXT", invocation.observationSections());
        return contextBaselineRecorder.begin(new ContextCaptureRequest(
                state.getSessionId(), state.getUserId(), null, null, agent,
                state.getStage() == null ? null : state.getStage().name(),
                invocation == null ? schemaVersion : invocation.schemaVersion(),
                requirementRevision, state.getConstraintRevision(), state.getPlanRevision(), sections));
    }

    /** 换一批：从备选池翻下一页（已勾选置顶、不重复）；返回 0=新页 1=翻完一轮从头再来 */
    public int nextHotelBatch(TravelState state) {
        List<HotelCandidate> pool = state.getHotelPool() == null ? List.of() : state.getHotelPool();
        Set<Long> picked = state.getPickedHotelIds();
        List<HotelCandidate> pickedItems = pool.stream()
                .filter(c -> picked.contains(c.getHotelId())).toList();
        List<HotelCandidate> unpicked = pool.stream()
                .filter(c -> !picked.contains(c.getHotelId())).toList();

        int cursor = state.getHotelCursor();
        int wrapped = 0;
        if (cursor >= unpicked.size()) {
            cursor = 0;
            wrapped = 1;
        }
        List<HotelCandidate> batch = new ArrayList<>(pickedItems);
        int need = Math.max(0, HOTEL_BATCH - batch.size());
        int take = Math.min(need, unpicked.size() - cursor);
        if (take > 0) {
            batch.addAll(unpicked.subList(cursor, cursor + take));
            cursor += take;
        }
        state.setHotelCursor(cursor);
        state.setHotelCandidates(batch);
        return wrapped;
    }

    /** 已选景点 + 美食的坐标几何中心（无坐标的网搜店不计入，避免空值污染中心点） */
    private double[] centerOf(TravelState state) {
        List<double[]> points = new ArrayList<>();
        if (state.getSelectedAttractionIds() != null && !state.getSelectedAttractionIds().isEmpty()) {
            attractionMapper.selectBatchIds(state.getSelectedAttractionIds())
                    .forEach(a -> {
                        if (a.getLng() != null && a.getLat() != null) {
                            points.add(new double[]{a.getLng(), a.getLat()});
                        }
                    });
        }
        if (state.getSelectedFoodIds() != null && !state.getSelectedFoodIds().isEmpty()) {
            restaurantMapper.selectBatchIds(state.getSelectedFoodIds())
                    .forEach(r -> {
                        if (r.getLng() != null && r.getLat() != null) {
                            points.add(new double[]{r.getLng(), r.getLat()});
                        }
                    });
        }
        if (points.isEmpty()) {
            return new double[]{0, 0};
        }
        return new double[]{
                points.stream().mapToDouble(p -> p[0]).average().orElse(0),
                points.stream().mapToDouble(p -> p[1]).average().orElse(0)
        };
    }

    private Map<String, Object> hotelPoolItem(TravelState state, Hotel h, double[] center) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", h.getId());
        m.put("name", h.getName());
        m.put("price", h.getPricePerNight());
        m.put("rating", h.getRating());
        m.put("level", h.getLevel());
        m.put("distanceKm", round(GeoUtils.distanceKm(center[0], center[1], h.getLng(), h.getLat())));
        m.put("features", h.getFeatures() == null ? "" : h.getFeatures());
        m.put("address", h.getAddress() == null ? "" : h.getAddress());
        m.put("distanceToAnchorKm",
                locationDistance(state, LocationConstraintSupport.HOTEL, h.getLng(), h.getLat()));
        return m;
    }

    private HotelCandidate hotelOf(TravelState state, Hotel h, double[] center, String why) {
        HotelCandidate c = new HotelCandidate();
        c.setHotelId(h.getId());
        c.setName(h.getName());
        c.setPricePerNight(h.getPricePerNight());
        c.setRating(h.getRating());
        c.setDistanceToCenter(distanceKmSafe(center, h.getLng(), h.getLat()));
        c.setFeature(h.getFeatures());
        c.setTags(h.getTags());
        c.setWhy(why);
        c.setDistanceToAnchor(
                locationDistance(state, LocationConstraintSupport.HOTEL, h.getLng(), h.getLat()));
        return c;
    }

    /** 距中心距离（公里）；任一端无坐标返回 null（网搜酒店无坐标，前端不展示距离） */
    private Double distanceKmSafe(double[] center, Double lng, Double lat) {
        if (center == null || lng == null || lat == null) {
            return null;
        }
        return round(GeoUtils.distanceKm(center[0], center[1], lng, lat));
    }

    private static boolean locationEligible(TravelState state, String scope, String name, String address,
                                            Double lng, Double lat) {
        return LocationConstraintSupport.matches(
                state == null ? null : state.getLocationConstraint(), scope, name, address, lng, lat);
    }

    private static Double locationDistance(TravelState state, String scope, Double lng, Double lat) {
        return LocationConstraintSupport.distanceKm(
                state == null ? null : state.getLocationConstraint(), scope, lng, lat);
    }

    /** 距离锚点越近，加分越高（最高 +1）；范围过滤仍是独立硬条件。 */
    private static double locationBonus(TravelState state, String scope, Double distanceKm) {
        LocationConstraint c = state == null ? null : state.getLocationConstraint();
        if (distanceKm == null || !LocationConstraintSupport.appliesTo(c, scope)
                || !LocationConstraint.RESOLVED.equals(c.getStatus()) || c.getRadiusKm() <= 0) {
            return 0;
        }
        return Math.max(0, 1 - distanceKm / c.getRadiusKm());
    }

    private double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
