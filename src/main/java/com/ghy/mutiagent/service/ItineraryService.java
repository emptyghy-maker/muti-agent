package com.ghy.mutiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.agent.ItineraryAgent;
import com.ghy.mutiagent.common.BizException;
import com.ghy.mutiagent.common.GeoUtils;
import com.ghy.mutiagent.common.JsonUtils;
import com.ghy.mutiagent.common.OpAbortException;
import com.ghy.mutiagent.common.PatchRejectException;
import com.ghy.mutiagent.common.ResultCode;
import com.ghy.mutiagent.model.AdjustPatchIntent;
import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.BudgetBreakdown;
import com.ghy.mutiagent.model.FeedbackRequest;
import com.ghy.mutiagent.model.ItineraryDetail;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.ItinerarySummary;
import com.ghy.mutiagent.model.PlaceKey;
import com.ghy.mutiagent.model.PlaceType;
import com.ghy.mutiagent.model.PatchOperation;
import com.ghy.mutiagent.model.PlanNode;
import com.ghy.mutiagent.model.StayBooking;
import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.model.UsageChannel;
import com.ghy.mutiagent.repository.entity.Attraction;
import com.ghy.mutiagent.repository.mapper.AttractionMapper;
import com.ghy.mutiagent.repository.entity.Destination;
import com.ghy.mutiagent.repository.mapper.DestinationMapper;
import com.ghy.mutiagent.repository.entity.Hotel;
import com.ghy.mutiagent.repository.mapper.HotelMapper;
import com.ghy.mutiagent.repository.entity.Itinerary;
import com.ghy.mutiagent.repository.entity.ItineraryFeedback;
import com.ghy.mutiagent.repository.mapper.ItineraryFeedbackMapper;
import com.ghy.mutiagent.repository.mapper.ItineraryMapper;
import com.ghy.mutiagent.repository.entity.Restaurant;
import com.ghy.mutiagent.repository.mapper.RestaurantMapper;
import com.ghy.mutiagent.rule.BudgetCalculator;
import com.ghy.mutiagent.rule.FatigueScorer;
import com.ghy.mutiagent.rule.ItineraryTextRenderer;
import com.ghy.mutiagent.rule.MealTimeChecker;
import com.ghy.mutiagent.rule.PlaceIndex;
import com.ghy.mutiagent.rule.PlaceKeyResolver;
import com.ghy.mutiagent.rule.PlanNodeRef;
import com.ghy.mutiagent.rule.PriceSnapshot;
import com.ghy.mutiagent.rule.ScheduleBuilder;
import com.ghy.mutiagent.rule.TripBilling;
import com.ghy.mutiagent.service.validation.ItineraryValidator;
import com.ghy.mutiagent.service.validation.PlanStructureValidator;
import com.ghy.mutiagent.security.AuthenticatedUser;
import com.ghy.mutiagent.trace.AgentTrace;
import com.ghy.mutiagent.trace.TraceContext;
import com.ghy.mutiagent.trace.TraceService;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 行程规划服务（阶段 D 核心）：
 *
 * LLM 生成骨架 → Java 硬约束兜底：
 * 1. placeId 白名单校验 + 名称回填（防编造）；
 * 2. 饭点检查：缺午餐/晚餐 → 自动插入最近顺路的已选饭店；
 * 3. 劳累度检查：综合分超阈值且缺休息点 → 自动插入低强度景点；
 * 4. 按时间排序、算每天劳累分与预计消费、渲染文本、落库 t_itinerary。
 */
@Service
public class ItineraryService {

    private static final Logger log = LoggerFactory.getLogger(ItineraryService.class);

    private final AttractionMapper attractionMapper;
    private final RestaurantMapper restaurantMapper;
    private final HotelMapper hotelMapper;
    private final ItineraryMapper itineraryMapper;
    private final ItineraryFeedbackMapper itineraryFeedbackMapper;
    private final DestinationMapper destinationMapper;
    private final ItineraryAgent itineraryAgent;
    private final TraceService traceService;
    private final UsageService usageService;
    private final ObjectMapper objectMapper;
    private final ItineraryCommitService commitService;

    /** Observability 薄埋点（可空：手动装配的测试进程为 null；模块关闭时内部 noop） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ghy.mutiagent.observability.collection.ObsInstrumentation obsInstrumentation;
    /** S08 修复引擎（可选装配）：未装配时保持 P0 语义（违规直接拒绝发布，不进入修复循环） */
    private ItineraryRepairEngine repairEngine;
    /** S09 补丁 Agent（可选装配）：模型提出白名单局部补丁，服务端逐项验证 */
    private com.ghy.mutiagent.agent.ItineraryPatchAgent patchAgent;
    /** S09 新候选召回（可选装配）：REPLACE_PLACE 的新地点必须来自召回候选池（禁忌/事实/预算同口径） */
    private CandidateService candidateService;

    /** 补丁 Agent 使用的模型（小 JSON 任务，快模型） */
    @Value("${llm.models.default}")
    private String patchModel;

    /** ItineraryAgent 绑定 sqlChatModel，记录用量时注明模型 */
    @Value("${llm.models.sql}")
    private String sqlModel;

    public ItineraryService(AttractionMapper attractionMapper,
                            RestaurantMapper restaurantMapper,
                            HotelMapper hotelMapper,
                            ItineraryMapper itineraryMapper,
                            ItineraryFeedbackMapper itineraryFeedbackMapper,
                            DestinationMapper destinationMapper,
                            ItineraryAgent itineraryAgent,
                            TraceService traceService,
                            UsageService usageService,
                            ObjectMapper objectMapper,
                            ItineraryCommitService commitService) {
        this.attractionMapper = attractionMapper;
        this.restaurantMapper = restaurantMapper;
        this.hotelMapper = hotelMapper;
        this.itineraryMapper = itineraryMapper;
        this.itineraryFeedbackMapper = itineraryFeedbackMapper;
        this.destinationMapper = destinationMapper;
        this.itineraryAgent = itineraryAgent;
        this.traceService = traceService;
        this.usageService = usageService;
        this.objectMapper = objectMapper;
        this.commitService = commitService;
    }

    /** 生成行程并落库：填充 state.plan / state.itineraryText / state.itineraryId（落库） */
    public void generate(TravelState state) {
        generateInternal(state, true, null, null);
    }

    /** 生成行程：模型 + 规则 + 校验；persist=false 时不落库（S06-A：调整流程先事务外规划，再交给提交服务短事务落库） */
    void generate(TravelState state, boolean persist) {
        generateInternal(state, persist, null, null);
    }

    private Map<String, Object> generateInternal(TravelState state, boolean persist,
                                                 CancelRegistry.CancelToken cancelToken,
                                                 com.ghy.mutiagent.trace.TraceMeta meta) {
        List<Attraction> attractions = state.getSelectedAttractionIds().isEmpty() ? List.of()
                : attractionMapper.selectBatchIds(state.getSelectedAttractionIds());
        List<Restaurant> restaurants = state.getSelectedFoodIds().isEmpty() ? List.of()
                : restaurantMapper.selectBatchIds(state.getSelectedFoodIds());
        List<Hotel> hotels = state.getSelectedHotelIds().isEmpty() ? List.of()
                : hotelMapper.selectBatchIds(state.getSelectedHotelIds());
        List<Attraction> restSpots = loadRestSpots(state);

        ItineraryPlan plan = null;
        boolean providerOk = false;
        // S08：修复引擎装配时，整个 operation 共用一份预算（调用配额/token/截止）
        OperationBudget budget = repairEngine == null ? null : repairEngine.newBudget();
        // S12：规划 attempt span（解析/验证分层状态由后续路径回填）
        AgentTrace plannerSpan = null;
        // S10：一次生成内的排程与账单共享同一路线事实快照（同一段行程同一 factId）
        com.ghy.mutiagent.service.route.RouteFactSnapshot facts = routeFactService == null ? null
                : new com.ghy.mutiagent.service.route.RouteFactSnapshot(routeFactService,
                        java.time.LocalDate.now().toString(), state.getConstraintRevision());
        // 各地点综合评分（来自候选池 AHP 评分）：跨类型统一索引（PlaceKey），供 LLM 选点与兜底排序参考
        Map<PlaceKey, Double> scoreById = PlaceIndex.scores(
                state.getAttractionPool(), state.getFoodPool(), state.getHotelPool());
        String usageStage = state.getAdjustContext() == null ? "ITINERARY" : "ADJUST";
        try {
            String prefJson = objectMapper.writeValueAsString(state.getPreference());
            String attrJson = objectMapper.writeValueAsString(attractions.stream()
                    .map(a -> Map.of("id", a.getId(), "name", a.getName(), "category", a.getCategory(),
                            "intensity", a.getIntensity(), "hours", a.getSuggestHours(), "price", a.getTicketPrice(),
                            "score", scoreById.getOrDefault(PlaceKey.of(PlaceType.ATTRACTION, a.getId()), 0.0)))
                    .toList());            String foodJson = objectMapper.writeValueAsString(restaurants.stream()
                    .map(r -> Map.of("id", r.getId(), "name", r.getName(), "cuisine", r.getCuisine(),
                            "score", scoreById.getOrDefault(PlaceKey.of(PlaceType.RESTAURANT, r.getId()), 0.0)))
                    .toList());
            String hotelJson = objectMapper.writeValueAsString(hotels.stream()
                    .map(h -> Map.of("id", h.getId(), "name", h.getName(), "price", h.getPricePerNight(),
                            "score", scoreById.getOrDefault(PlaceKey.of(PlaceType.HOTEL, h.getId()), 0.0)))
                    .toList());
            String rules = buildRules(state, restSpots);

            Integer prefDays = state.getPreference().getDays();
            if (repairEngine == null) {
                // ==================== P0 语义（未装配修复引擎时完全不变） ====================
                TraceContext ctx = traceService.newTrace(state.getSessionId(), "行程规划");
                long startNs = System.nanoTime();
                try {
                    Result<String> r = itineraryAgent.plan(prefJson, attrJson, foodJson, hotelJson, rules);
                    long cost = (System.nanoTime() - startNs) / 1_000_000;
                    // S03：提供方调用成功——此后输出不合格一律拒绝发布，不静默替换为规则方案
                    providerOk = true;
                    String raw = r.content();
                    if (raw != null) {
                        plan = parsePlanJson(raw);
                    }
                    ctx.add(AgentTrace.success("ItineraryAgent", cost, r.tokenUsage()));
                    ctx.finish("SUCCESS");
                    int[] tk = tokenCounts(r.tokenUsage());
                    usageService.recordAgent(auditSessionId(state), state.getUserId(), state.getUsername(),
                            usageStage, "行程规划", "ItineraryAgent", r.tokenUsage(), cost, "SUCCESS", null,
                            sqlModel, UsageChannel.AGENT, UsageService.clip(raw, 2000));
                    state.addTurnUsage(sqlModel, tk[0], tk[1], UsageChannel.AGENT);
                } catch (Exception e) {
                    long cost = (System.nanoTime() - startNs) / 1_000_000;
                    ctx.add(AgentTrace.failure("ItineraryAgent", cost,
                            e.getClass().getName() + ": " + e.getMessage()));
                    ctx.finish("FAILED");
                    usageService.recordAgent(auditSessionId(state), state.getUserId(), state.getUsername(),
                            usageStage, "行程规划", "ItineraryAgent", null, cost, "FAILED",
                            e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()),
                            sqlModel, UsageChannel.RULE_FALLBACK, null);
                    state.addTurnUsage(null, 0, 0, UsageChannel.RULE_FALLBACK);
                    log.warn("行程规划 LLM 调用或输出处理失败（{}）", e.getClass().getName(), e);
                } finally {
                    traceService.finish(ctx);
                }
            } else {
                // S08/S11：规划调用统一入口（共享预算预留 + 计量 + trace + 截止/取消守卫）
                checkCancelAndDeadline(budget, cancelToken);
                if (prefDays != null && prefDays > repairEngine.config().chunkDays()) {
                    // S11：长行程按天分块——每块一次规划调用（请求天数 ≤ chunkDays）；
                    // 截断/缺块 = INCOMPLETE_PLAN（不字符串修补），块级重试消耗共享预算
                    plan = chunkedPlan(state, budget, usageStage, prefJson, attrJson, foodJson,
                            hotelJson, rules, prefDays, cancelToken, meta);
                    providerOk = true;
                } else {
                    PlannerInvocation inv = invokePlanner(budget, state, usageStage,
                            prefJson, attrJson, foodJson, hotelJson, rules, meta);
                    plannerSpan = inv == null ? null : inv.span();
                    if (inv != null && inv.result() != null) {
                        providerOk = true;
                        plan = null;
                        try {
                            plan = parsePlanJson(inv.result().content());
                        } catch (Exception parseError) {
                            plan = null;
                        }
                        if (inv.span() != null) {
                            // S12：解析结果分层记录（provider=SUCCESS 与 parse=FAILED 分开）
                            inv.span().setParseStatus(plan == null ? AgentTrace.FAILED : AgentTrace.SUCCESS);
                        }
                        if (plan == null) {
                            // S12：提供方成功但输出无法解析——不进入验证循环，持久化保持 NOT_ATTEMPTED
                            throw new BizException(ResultCode.PLAN_INVALID.getCode(),
                                    "行程规划输出无法解析为合法行程 JSON");
                        }
                    }
                }
            }
        } catch (OpAbortException abort) {
            throw abort;
        } catch (Exception e) {
            log.warn("行程规划数据准备失败，使用规则兜底: {}", e.getMessage());
        }

        int expectedDays = state.getPreference().getDays() == null
                ? (plan == null || plan.getDays() == null ? 0 : plan.getDays().size())
                : state.getPreference().getDays();

        if (repairEngine == null) {
            // ==================== P0 语义（未装配修复引擎时完全不变） ====================
            if (!providerOk) {
                // 提供方调用本身失败：规则兜底（既有行为）
                plan = fallbackPlan(state, attractions, restaurants, hotels, scoreById);
            } else {
                // S03：提供方成功则结构验证是发布唯一关口，违规拒绝发布（不落库、不静默降级）
                List<String> violations = PlanStructureValidator.validate(plan, expectedDays);
                if (!violations.isEmpty()) {
                    log.warn("[Itinerary] Agent 输出未通过结构验证：{}（拒绝发布）", String.join("；", violations));
                    throw new BizException(ResultCode.PLAN_INVALID.getCode(),
                            "行程结构验证未通过：" + String.join("；", violations));
                }
            }
            // S05：跨类型坐标索引，排程与账单共用同一套坐标解析
            Map<PlaceKey, double[]> coords = PlaceIndex.coords(attractions, restaurants, hotels, restSpots);
            postProcess(state, plan, attractions, restaurants, hotels, restSpots, coords, facts);

            // S05：住宿夜次（days-1 晚）与全程账单——预算检查唯一入口
            int party = state.getPreference().getPeopleCount() == null ? 2 : state.getPreference().getPeopleCount();
            Hotel hotel = hotels.isEmpty() ? null : hotels.get(0);
            int days = state.getPreference().getDays() == null ? plan.getDays().size() : state.getPreference().getDays();
            List<StayBooking> stays = TripBilling.defaultStays(days, hotel, TripBilling.defaultRooms(party));
            BudgetBreakdown bill = TripBilling.calculateTrip(plan, stays, party,
                    priceSnapshot(attractions, restaurants, hotels, restSpots), coords,
                    state.getPreference().getTotalBudget(), facts);
            plan.setStays(stays);
            plan.setBudgetBreakdown(bill);
            if (bill.isOverLimit()) {
                log.warn("[Itinerary] 已核实费用 {} 元 超过预算 {} 元（发布检查将阻止发布）",
                        bill.getKnownSubtotal(), bill.getTotalBudget());
            }

            // S04：发布前校验（时序重叠/开放时间/饭点/预算/硬限制/疲劳），不通过拒绝发布
            Map<PlaceKey, String> openTimes = new HashMap<>();
            attractions.forEach(a -> openTimes.put(PlaceKey.of(PlaceType.ATTRACTION, a.getId()), a.getOpenTime()));
            restSpots.forEach(a -> openTimes.put(PlaceKey.of(PlaceType.ATTRACTION, a.getId()), a.getOpenTime()));
            Map<Long, Attraction> attById = attractions.stream().collect(Collectors.toMap(Attraction::getId, a -> a));
            restSpots.forEach(a -> attById.put(a.getId(), a));
            boolean hardFatigueOverload = "偏弱".equals(state.getPreference().getEnergyLevel())
                    && overloadedAfterRest(plan, attById);
            ItineraryValidator.ValidationResult check = ItineraryValidator.validate(plan, state.getPreference(),
                    state.getRequirementSnapshot(), state.getPreference().getTotalBudget(), bill.getKnownSubtotal(),
                    openTimes, attById, hardFatigueOverload);
            if (!check.publishable()) {
                log.warn("[Itinerary] 发布检查未通过：violations={} needsConfirmation={}（拒绝发布）",
                        check.violations(), check.needsConfirmation());
                throw new BizException(ResultCode.PLAN_INVALID.getCode(),
                        "发布检查未通过：" + String.join("；", check.violations())
                                + (check.needsConfirmation() ? "；存在无法自动核实的硬条件，需用户确认" : ""));
            }

            state.setPlan(plan);
            state.setItineraryText(ItineraryTextRenderer.render(plan));
            if (persist) {
                persist(state, plan);
            }
            log.info("[Itinerary][sessionId={}] 行程生成完成，{} 天，总消费 {}", state.getSessionId(),
                    plan.getDays().size(), ItineraryTextRenderer.totalCost(plan));
            return null;
        }

        // ==================== S08：确定性验证 + 有限修复（共享预算） ====================
        if (!providerOk) {
            // 提供方失败且未超截止：沿用规则兜底（既有行为）；兜底方案同样过下方验证与发布检查
            plan = fallbackPlan(state, attractions, restaurants, hotels, scoreById);
        }
        ItineraryPlan draft = plan;
        // S12：当前草稿来源 span（验证结果回填到产出该草稿的 attempt）
        AgentTrace draftSpan = plannerSpan;
        Map<PlaceKey, double[]> coords = PlaceIndex.coords(attractions, restaurants, hotels, restSpots);
        Map<String, Object> evidence = new LinkedHashMap<>();
        List<String> firstCodes = null;
        int maxRepairs = repairEngine.config().maxRepairs();
        for (int attempt = 0; attempt <= maxRepairs; attempt++) {
            // 调用前/修复前复查截止与取消（调用后与提交前由各自路径复查）
            checkCancelAndDeadline(budget, cancelToken);
            List<ItineraryRepairEngine.RepairViolation> violations = validateWhole(state, draft, expectedDays,
                    attractions, restaurants, hotels, restSpots, coords, facts);
            List<String> codes = violations.stream().map(ItineraryRepairEngine.RepairViolation::code).toList();
            // S12：验证结果分层记录在当前草稿来源 span（provider 与 validation 分开）
            if (draftSpan != null) {
                draftSpan.setValidationStatus(codes.isEmpty() ? AgentTrace.SUCCESS : AgentTrace.FAILED);
            }
            obsValidation(meta, state, codes, attempt);
            if (firstCodes == null) {
                firstCodes = codes;
            }
            // B 方案：疲劳超载单独分流——确定性判定且模型修复无杠杆（休息不计分、景点已锁定），
            // 与可修复违规分离：可修复的照常走修复循环，疲劳仅剩自己时进入待用户确认
            List<ItineraryRepairEngine.RepairViolation> fatigueViolations = violations.stream()
                    .filter(v -> ItineraryValidator.HARD_FATIGUE_EXCEEDED.equals(v.code())).toList();
            List<ItineraryRepairEngine.RepairViolation> repairableViolations = violations.stream()
                    .filter(v -> !ItineraryValidator.HARD_FATIGUE_EXCEEDED.equals(v.code())).toList();
            if (violations.isEmpty()) {
                // 提交前复查取消：取消后晚到的合法结果也不得进入提交
                if (cancelToken != null && cancelToken.isCancelled()) {
                    throw cancelledAbort();
                }
                // 确定性合法：直接走 P0 提交协议，不增加任何「更智能」的模型审阅节点
                state.setPlan(draft);
                state.setItineraryText(ItineraryTextRenderer.render(draft));
                if (persist) {
                    persist(state, draft);
                }
                evidence.put("validationCodes", firstCodes);
                evidence.put("finalValidationCodes", codes);
                evidence.put("repairAttempts", attempt);
                evidence.put("reasonCodes", List.of());
                log.info("[Itinerary][sessionId={}] 行程生成完成（修复 {} 次），{} 天", state.getSessionId(),
                        attempt, draft.getDays().size());
                return evidence;
            }
            if (!fatigueViolations.isEmpty() && repairableViolations.isEmpty()) {
                // B 方案：疲劳超载是唯一违规时不再拒发——留存完整草稿（已补休息/饭点/账单）进入待确认，
                // 由用户知情确认后发布；不落库 plan、不推进 DONE
                double fatigueScore = maxFatigueDayScore(draft, attractions, restSpots);
                state.setPendingPlan(draft);
                state.setPendingFatigueConfirm(true);
                state.setPendingFatigueScore(fatigueScore);
                evidence.put("validationCodes", firstCodes);
                evidence.put("finalValidationCodes", codes);
                evidence.put("repairAttempts", attempt);
                evidence.put("reasonCodes", List.of());
                evidence.put("awaitingFatigueConfirm", true);
                log.info("[Itinerary][sessionId={}] 疲劳超载（疲劳分 {}）：行程草稿进入待用户确认",
                        state.getSessionId(), fatigueScore);
                return evidence;
            }
            if (codes.contains(ItineraryValidator.BUDGET_EXCEEDED)) {
                // 预算超限走确定性换店修复（修复约束禁止引入新地点，模型对预算违规无杠杆）
                if (budgetSwap(draft, restaurants, state.getPreference())) {
                    log.info("[Itinerary][sessionId={}] 预算超限：已用更便宜的已选餐厅确定性替换，进入下一轮重验",
                            state.getSessionId());
                    continue;
                }
                // 已选餐厅内无法压到预算内：留存草稿进入待确认（知情放行/返回调整），不进模型修复
                BigDecimal over = budgetOverAmount(draft);
                state.setPendingPlan(draft);
                state.setPendingBudgetConfirm(true);
                state.setPendingBudgetOver(over);
                evidence.put("validationCodes", firstCodes);
                evidence.put("finalValidationCodes", codes);
                evidence.put("repairAttempts", attempt);
                evidence.put("reasonCodes", List.of());
                evidence.put("awaitingBudgetConfirm", true);
                log.info("[Itinerary][sessionId={}] 预算超支（超 {} 元）：行程草稿进入待用户确认",
                        state.getSessionId(), over);
                return evidence;
            }
            boolean repairable = repairableViolations.stream().allMatch(ItineraryRepairEngine.RepairViolation::repairable);
            if (attempt == maxRepairs || !repairable) {
                // 不可修复或修复上限耗尽：保留草稿与冲突说明，不保存为可用行程，交由用户确认
                List<String> reasons = new ArrayList<>(budget.reasonCodes());
                if (attempt == maxRepairs) {
                    reasons.add("REPAIR_ATTEMPTS_EXCEEDED");
                }
                Map<String, Object> abortEvidence = new LinkedHashMap<>();
                abortEvidence.put("validationCodes", firstCodes);
                abortEvidence.put("finalValidationCodes", codes);
                String userMessage;
                if (codes.contains(ItineraryValidator.HARD_FATIGUE_EXCEEDED)) {
                    userMessage = fatigueAbortMessage(draft, attractions, restSpots);
                } else {
                    userMessage = "行程生成未通过发布检查：" + String.join("；", codes)
                            + "（已自动修复 " + attempt + " 次），请调整选择后重试";
                }
                throw new OpAbortException(ResultCode.PLAN_INVALID, userMessage, "NEEDS_CONFIRMATION", reasons,
                        codes, abortEvidence);
            }
            // 修复前复查取消：取消后不再发起任何模型步骤（晚到结果被取消检查拒绝）
            if (cancelToken != null && cancelToken.isCancelled()) {
                throw cancelledAbort();
            }
            // 修复：只拿受影响计划、可修复 violations 与不可变条件；调用进共享预算（预留不足 → 终止）
            String repairContext = repairEngine.buildRepairContext(draft, repairableViolations, state);
            TraceContext rctx = traceService.newTrace(state.getSessionId(), "行程修复");
            // S12：修复是同一 operation 的新 provider attempt（重试共享 operation，attemptId 递增）
            if (meta != null) {
                com.ghy.mutiagent.trace.TraceRegistry reg = traceService.registry();
                int attemptId = reg == null ? 1 : reg.nextAttemptId(meta.operationId());
                rctx.setSpanId(meta.operationId() + "-a" + attemptId);
                rctx.setParentSpanId(meta.operationId());
                rctx.setOperationId(meta.operationId());
                rctx.setOwnerId(meta.ownerId());
                rctx.setProviderAttemptId(attemptId);
                rctx.setKind("PROVIDER");
                rctx.setPromptVersion(meta.promptVersion());
                rctx.setModelVersion(meta.modelVersion());
                rctx.setConstraintRevision(meta.constraintRevision());
                rctx.setSnapshotHash(meta.snapshotHash());
                traceService.register(rctx);
            }
            long rstartNs = System.nanoTime();
            Result<String> repaired = null;
            Exception repairError = null;
            AgentTrace repairSpan = null;
            // 首轮修复用快模型（秒级出稿），仍不过再升级强模型兜底一轮
            boolean heavyRepair = attempt > 0;
            String repairModel = heavyRepair ? sqlModel : patchModel;
            try {
                repaired = repairEngine.repair(budget, repairContext, heavyRepair);
            } catch (Exception e) {
                repairError = e;
                // 修复提供方失败且已越过截止 → 按截止终止（不再重试制造重复计费）
                if (budget.deadlineExceeded()) {
                    throw new OpAbortException(ResultCode.DEADLINE_EXCEEDED, "DEADLINE_EXCEEDED",
                            List.of("DEADLINE_EXCEEDED"), List.of(), Map.of());
                }
                log.warn("[Itinerary] 修复调用失败（{}），进入下一轮", e.getMessage());
            } finally {
                long cost = (System.nanoTime() - rstartNs) / 1_000_000;
                if (repairError == null) {
                    repairSpan = AgentTrace.success("ItineraryRepairAgent", cost,
                            repaired == null ? null : repaired.tokenUsage());
                    rctx.add(repairSpan);
                    rctx.finish("SUCCESS");
                    obsRepairRound(meta, state, attempt, "SUCCESS");
                    usageService.recordAgent(auditSessionId(state), state.getUserId(), state.getUsername(),
                            usageStage, "行程修复", "ItineraryRepairAgent",
                            repaired == null ? null : repaired.tokenUsage(), cost, "SUCCESS", null,
                            repairModel, UsageChannel.AGENT, UsageService.clip(repairContext, 2000));
                } else {
                    repairSpan = AgentTrace.failure("ItineraryRepairAgent", cost,
                            repairError.getClass().getName() + ": " + repairError.getMessage());
                    rctx.add(repairSpan);
                    rctx.finish("FAILED");
                    obsRepairRound(meta, state, attempt, "FAILED");
                    usageService.recordAgent(auditSessionId(state), state.getUserId(), state.getUsername(),
                            usageStage, "行程修复", "ItineraryRepairAgent", null, cost, "FAILED",
                            repairError.getClass().getSimpleName()
                                    + (repairError.getMessage() == null ? "" : ": " + repairError.getMessage()),
                            repairModel, UsageChannel.RULE_FALLBACK, null);
                }
                traceService.finish(rctx);
            }
            if (repairError instanceof OpAbortException abort) {
                throw abort;
            }
            if (repairError != null) {
                continue;
            }
            // 修复输出必须重新过全局验证（下一轮循环）——合法 JSON 之外的一律视为无进展
            ItineraryPlan next = parsePlanJson(repaired.content());
            if (repairSpan != null) {
                repairSpan.setParseStatus(next == null ? AgentTrace.FAILED : AgentTrace.SUCCESS);
            }
            if (next == null) {
                log.warn("[Itinerary] 修复输出无法解析为合法行程 JSON，进入下一轮");
                continue;
            }
            draft = next;
            draftSpan = repairSpan;
        }
        // 防御性终止（循环必然以提交或终止退出）
        throw new OpAbortException(ResultCode.PLAN_INVALID, "NEEDS_CONFIRMATION",
                new ArrayList<>(budget.reasonCodes()), firstCodes == null ? List.of() : firstCodes, Map.of());
    }

    /** S12 规划调用结果：结果 + 本次调用的 span/ctx（解析与验证分层状态由调用方回填） */
    private record PlannerInvocation(Result<String> result, TraceContext ctx, AgentTrace span) {
    }

    /**
     * S08/S11/S12 规划调用统一入口：调用前共享预算预留（不足不发请求）+ 成功/失败计量与 trace。
     * 携带 operation 关联元数据（spanId/attemptId/版本快照）；预算预留失败/越截止 → 终止语义直达上层；
     * 提供方故障（未越截止）返回 null 供兜底/重试。
     */
    private PlannerInvocation invokePlanner(OperationBudget budget, TravelState state, String usageStage,
            String prefJson, String attrJson, String foodJson, String hotelJson, String rules,
            com.ghy.mutiagent.trace.TraceMeta meta) {
        TraceContext ctx = traceService.newTrace(state.getSessionId(), "行程规划");
        // S12：规划是同一 operation 的新 provider attempt（重试共享 operation，attemptId 递增）
        if (meta != null) {
            com.ghy.mutiagent.trace.TraceRegistry reg = traceService.registry();
            int attemptId = reg == null ? 1 : reg.nextAttemptId(meta.operationId());
            ctx.setSpanId(meta.operationId() + "-a" + attemptId);
            ctx.setParentSpanId(meta.operationId());
            ctx.setOperationId(meta.operationId());
            ctx.setOwnerId(meta.ownerId());
            ctx.setProviderAttemptId(attemptId);
            ctx.setKind("PROVIDER");
            ctx.setPromptVersion(meta.promptVersion());
            ctx.setModelVersion(meta.modelVersion());
            ctx.setConstraintRevision(meta.constraintRevision());
            ctx.setSnapshotHash(meta.snapshotHash());
            traceService.register(ctx);
        }
        long startNs = System.nanoTime();
        try {
            // S08：规划调用进共享预算——调用前原子预留（输入估计+最大输出），不足不发请求
            Result<String> r = repairEngine.plannerCall(budget, ItineraryRepairEngine.estimateTokens(
                            prefJson + attrJson + foodJson + hotelJson + rules),
                    () -> itineraryAgent.plan(prefJson, attrJson, foodJson, hotelJson, rules));
            long cost = (System.nanoTime() - startNs) / 1_000_000;
            AgentTrace span = AgentTrace.success("ItineraryAgent", cost, r.tokenUsage());
            span.setAnswer(UsageService.clip(r.content(), 2000));
            ctx.add(span);
            ctx.finish("SUCCESS");
            int[] tk = tokenCounts(r.tokenUsage());
            usageService.recordAgent(auditSessionId(state), state.getUserId(), state.getUsername(),
                    usageStage, "行程规划", "ItineraryAgent", r.tokenUsage(), cost, "SUCCESS", null,
                    sqlModel, UsageChannel.AGENT, UsageService.clip(r.content(), 2000));
            state.addTurnUsage(sqlModel, tk[0], tk[1], UsageChannel.AGENT);
            return new PlannerInvocation(r, ctx, span);
        } catch (OpAbortException abort) {
            // 预算预留失败（截止/配额/token）：终止语义直达上层，不兜底、不重试
            AgentTrace span = AgentTrace.failure("ItineraryAgent",
                    (System.nanoTime() - startNs) / 1_000_000,
                    abort.getOpStatus() + ":" + abort.getReasonCodes());
            ctx.add(span);
            ctx.finish("FAILED");
            throw abort;
        } catch (Exception e) {
            long cost = (System.nanoTime() - startNs) / 1_000_000;
            AgentTrace span = AgentTrace.failure("ItineraryAgent", cost,
                    e.getClass().getName() + ": " + e.getMessage());
            ctx.add(span);
            ctx.finish("FAILED");
            usageService.recordAgent(auditSessionId(state), state.getUserId(), state.getUsername(),
                    usageStage, "行程规划", "ItineraryAgent", null, cost, "FAILED",
                    e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()),
                    sqlModel, UsageChannel.RULE_FALLBACK, null);
            state.addTurnUsage(null, 0, 0, UsageChannel.RULE_FALLBACK);
            log.warn("行程规划 LLM 调用或输出处理失败（{}）", e.getClass().getName(), e);
            // S08：提供方返回后已越过截止 → 不再兜底、不再修复，按截止终止
            if (budget.deadlineExceeded()) {
                throw new OpAbortException(ResultCode.DEADLINE_EXCEEDED, "DEADLINE_EXCEEDED",
                        List.of("DEADLINE_EXCEEDED"), List.of(), Map.of());
            }
            return null;
        } finally {
            traceService.finish(ctx);
        }
    }

    /**
     * S11 长行程分块规划：每块请求天数 ≤ chunkDays，块提示携带边界上下文（住宿/日期/剩余预算/锁定项），
     * 合并后统一走全局验证。块输出被截断或缺块视为不完整（INCOMPLETE_PLAN），绝不字符串补括号恢复；
     * 块级重试消耗共享调用预算，不每块重置。
     */
    private ItineraryPlan chunkedPlan(TravelState state, OperationBudget budget, String usageStage,
            String prefJson, String attrJson, String foodJson, String hotelJson, String baseRules,
            int totalDays, CancelRegistry.CancelToken cancelToken,
            com.ghy.mutiagent.trace.TraceMeta meta) {
        int chunkDays = repairEngine.config().chunkDays();
        List<DailyPlan> merged = new ArrayList<>();
        try {
            for (int start = 1; start <= totalDays; ) {
                int end = Math.min(totalDays, start + chunkDays - 1);
                String rules = chunkRules(baseRules, start, end, totalDays);
                List<DailyPlan> wanted = List.of();
                while (wanted.isEmpty()) {
                    checkCancelAndDeadline(budget, cancelToken);
                    PlannerInvocation inv = invokePlanner(budget, state, usageStage,
                            prefJson, attrJson, foodJson, hotelJson, rules, meta);
                    Result<String> r = inv == null ? null : inv.result();
                    if (r == null) {
                        // 提供方故障且未越截止：重试该块（预留失败/越截止已在 invokePlanner 中终止）
                        continue;
                    }
                    // S11：分块输出必须完整合法 JSON——截断/非法 = 不完整，严格解析，
                    // 绝不字符串补括号恢复（与主路径的宽容截断修复语义不同：分块缺天无法局部修复）
                    ItineraryPlan parsed = JsonUtils.parseStrict(r.content(), ItineraryPlan.class);
                    if (inv.span() != null) {
                        // S12：分块 attempt 的解析状态（严格解析，不做补括号）
                        inv.span().setParseStatus(parsed == null ? AgentTrace.FAILED : AgentTrace.SUCCESS);
                    }
                    wanted = parsed == null ? List.of() : daysInRange(parsed, start, end);
                    if (wanted.isEmpty()) {
                        // 截断/缺块：不完整——不以字符串补括号恢复，块级重试（消耗总调用数）
                        log.warn("[Itinerary] 第{}天分块输出不完整（截断或缺失），重试该块（共享预算）", start);
                    }
                }
                merged.addAll(wanted);
                start = end + 1;
            }
        } catch (OpAbortException abort) {
            if (TravelOperationService.STATUS_CANCELLED.equals(abort.getOpStatus())) {
                throw abort;
            }
            // 分块因预算/截止终止：归并 INCOMPLETE_PLAN——长行程未完整生成，不提交部分结果
            List<String> reasons = new ArrayList<>(abort.getReasonCodes());
            if (!reasons.contains("INCOMPLETE_PLAN")) {
                reasons.add("INCOMPLETE_PLAN");
            }
            throw new OpAbortException(ResultCode.PLAN_INVALID, abort.getOpStatus(), reasons,
                    abort.getViolations(), abort.getEvidence());
        }
        ItineraryPlan plan = new ItineraryPlan();
        plan.setDays(merged);
        return plan;
    }

    /** S11 分块提示：块范围标记 + 边界上下文（住宿、日期、剩余预算、已锁定项），并要求只输出块内天数 */
    static String chunkRules(String baseRules, int start, int end, int totalDays) {
        return baseRules
                + "\n\n【分块】本块规划范围：第" + start + "天 至 第" + end + "天（全行程共 " + totalDays + " 天）。"
                + "\n只输出本块范围内各天的完整安排，范围外的天数一律不要输出。"
                + "住宿、日期、剩余预算与已锁定项以上文为准；跨天边界请与相邻块衔接（当天住宿/次日起点）。";
    }

    /** S11 块内天数过滤：块输出只保留请求范围内的天（越界输出一律丢弃，不做字符串修补） */
    static List<DailyPlan> daysInRange(ItineraryPlan plan, int start, int end) {
        if (plan == null || plan.getDays() == null) {
            return List.of();
        }
        return plan.getDays().stream()
                .filter(d -> d.getDayIndex() >= start && d.getDayIndex() <= end)
                .toList();
    }

    /** S11 调用前守卫：截止与取消任一触发即终止（后续模型步骤与晚到结果都被拒绝） */
    private void checkCancelAndDeadline(OperationBudget budget, CancelRegistry.CancelToken cancelToken) {
        if (budget.deadlineExceeded()) {
            throw new OpAbortException(ResultCode.DEADLINE_EXCEEDED, "DEADLINE_EXCEEDED",
                    List.of("DEADLINE_EXCEEDED"), List.of(), Map.of());
        }
        if (cancelToken != null && cancelToken.isCancelled()) {
            throw cancelledAbort();
        }
    }

    private OpAbortException cancelledAbort() {
        return new OpAbortException(ResultCode.OPERATION_CANCELLED, "CANCELLED",
                List.of("CANCELLED"), List.of(), Map.of());
    }

    /** 宽容解析行程 JSON（统一 Agent 输出解析层）：tracked 主路径 + 嵌套泛化查找 */
    // ==================== Observability 薄埋点（可空，无副作用） ====================

    private void obsValidation(com.ghy.mutiagent.trace.TraceMeta meta, TravelState state,
                               List<String> codes, int round) {
        if (obsInstrumentation != null) {
            obsInstrumentation.validation(meta == null ? null : meta.operationId(),
                    state.getSessionId(), state.getUserId(), codes, round, Map.of());
        }
    }

    private void obsRepairRound(com.ghy.mutiagent.trace.TraceMeta meta, TravelState state,
                                int attempt, String outcome) {
        if (obsInstrumentation != null) {
            obsInstrumentation.repairRound(meta == null ? null : meta.operationId(),
                    state.getSessionId(), state.getUserId(), attempt, outcome);
        }
    }

    private void obsPatchEvent(String operationId, TravelState state, boolean proposed,
                               String outcome, Map<String, Object> summary) {
        if (obsInstrumentation != null) {
            obsInstrumentation.patchEvent(operationId, state.getSessionId(), state.getUserId(),
                    proposed, outcome, summary);
        }
    }

    private void obsCommitResult(String operationId, TravelState state, String outcome,
                                 Map<String, Object> summary) {
        if (obsInstrumentation != null) {
            obsInstrumentation.commitResult(operationId, state.getSessionId(), state.getUserId(),
                    outcome, summary);
        }
    }

    private ItineraryPlan parsePlanJson(String raw) {
        if (raw == null) {
            return null;
        }
        JsonUtils.ParseTracked<ItineraryPlan> tracked = JsonUtils.parseTracked(raw, ItineraryPlan.class);
        ItineraryPlan plan = tracked == null ? null : tracked.value();
        if (plan == null || plan.getDays() == null || plan.getDays().isEmpty()) {
            // 模型偶发把 days 包在别的键里：统一解析层泛化查找再解析
            plan = AgentOutputParser.nestedItinerary(JsonUtils.readTree(raw));
        }
        return plan;
    }

    /**
     * S08 全局验证：结构 → 后处理（补餐/去重/时间轴）→ 账单 → 发布检查，统一为修复违规。
     * 硬条件无法判定（needsConfirmation）不可由模型自行修复，按待确认处理。
     */
    private List<ItineraryRepairEngine.RepairViolation> validateWhole(TravelState state, ItineraryPlan draft,
            int expectedDays, List<Attraction> attractions, List<Restaurant> restaurants,
            List<Hotel> hotels, List<Attraction> restSpots, Map<PlaceKey, double[]> coords,
            com.ghy.mutiagent.service.route.RouteFactSnapshot facts) {
        List<String> structure = PlanStructureValidator.validate(draft, expectedDays);
        if (!structure.isEmpty()) {
            // S03/S08：结构违规不可修复——保持 P0 发布关口语义（422 拒绝发布，不进入修复循环）
            log.warn("[Itinerary] Agent 输出未通过结构验证：{}（拒绝发布）", String.join("；", structure));
            throw new BizException(ResultCode.PLAN_INVALID.getCode(),
                    "行程结构验证未通过：" + String.join("；", structure));
        }
        postProcess(state, draft, attractions, restaurants, hotels, restSpots, coords, facts);
        int party = state.getPreference().getPeopleCount() == null ? 2 : state.getPreference().getPeopleCount();
        Hotel hotel = hotels.isEmpty() ? null : hotels.get(0);
        int days = state.getPreference().getDays() == null ? draft.getDays().size() : state.getPreference().getDays();
        List<StayBooking> stays = TripBilling.defaultStays(days, hotel, TripBilling.defaultRooms(party));
        BudgetBreakdown bill = TripBilling.calculateTrip(draft, stays, party,
                priceSnapshot(attractions, restaurants, hotels, restSpots), coords,
                state.getPreference().getTotalBudget(), facts);
        draft.setStays(stays);
        draft.setBudgetBreakdown(bill);
        if (bill.isOverLimit()) {
            log.warn("[Itinerary] 已核实费用 {} 元 超过预算 {} 元（发布检查将阻止发布）",
                    bill.getKnownSubtotal(), bill.getTotalBudget());
        }
        Map<PlaceKey, String> openTimes = new HashMap<>();
        attractions.forEach(a -> openTimes.put(PlaceKey.of(PlaceType.ATTRACTION, a.getId()), a.getOpenTime()));
        restSpots.forEach(a -> openTimes.put(PlaceKey.of(PlaceType.ATTRACTION, a.getId()), a.getOpenTime()));
        Map<Long, Attraction> attById = attractions.stream().collect(Collectors.toMap(Attraction::getId, a -> a));
        restSpots.forEach(a -> attById.put(a.getId(), a));
        boolean hardFatigueOverload = "偏弱".equals(state.getPreference().getEnergyLevel())
                && overloadedAfterRest(draft, attById);
        ItineraryValidator.ValidationResult check = ItineraryValidator.validate(draft, state.getPreference(),
                state.getRequirementSnapshot(), state.getPreference().getTotalBudget(), bill.getKnownSubtotal(),
                openTimes, attById, hardFatigueOverload);
        if (check.needsConfirmation()) {
            return List.of(new ItineraryRepairEngine.RepairViolation(
                    "NEEDS_CONFIRMATION", 0, List.of(), false));
        }
        return ItineraryRepairEngine.unify(check);
    }

    // ==================== S09：局部补丁调整（白名单操作 + 稳定 nodeId + 全局复验） ====================

    /**
     * 补丁调整核心：读旧计划 → 模型提出补丁（服务端逐项验证白名单/范围/版本）→
     * REPLACE_PLACE 新地点走步骤 1 同口径召回并验证候选证据 → 在副本上应用 →
     * 未触及节点语义比较 → 整份计划复验（结构/预算/开放时间/硬限制/疲劳，不注入不重排）。
     * 结果只写入 adjState.plan（不落库）；T2 由操作协议按 baseRevision/attemptNo 提交新版本。
     * 任何拒绝都不覆盖旧版本；evidence 随草案持久化。
     */
    public Map<String, Object> applyPatch(TravelState adjState, Itinerary oldRow, TravelState sessionState) {
        if (patchAgent == null) {
            throw new PatchRejectException(ItineraryPatchEngine.ERR_PATCH_OP_UNSUPPORTED,
                    "局部补丁能力未启用");
        }
        try {
            ItineraryPlan original = objectMapper.readValue(oldRow.getPlanJson(), ItineraryPlan.class);
            assignNodeIds(original);
            int baseRevision = oldRow.getVersion() == null ? 1 : oldRow.getVersion();

            // 1. 模型提出补丁（快模型小 JSON 任务）
            String patchContext = buildPatchContext(original, adjState.getAdjustContext(), baseRevision);
            AdjustPatchIntent intent = proposePatch(adjState, patchContext);
            if (intent == null) {
                throw new PatchRejectException(ItineraryPatchEngine.ERR_PATCH_VALIDATION_FAILED,
                        "补丁 Agent 输出无法解析");
            }
            // 2. 服务端逐项验证：操作白名单、nodeId 存在、范围（targetDays）
            String scopeError = ItineraryPatchEngine.validateOps(original, intent);
            if (scopeError != null) {
                String code;
                if (scopeError.contains("超出声明范围")) {
                    code = ItineraryPatchEngine.ERR_PATCH_OUT_OF_SCOPE;
                } else if (scopeError.contains("节点不存在")) {
                    code = ItineraryPatchEngine.ERR_PATCH_NODE_NOT_FOUND;
                } else {
                    code = ItineraryPatchEngine.ERR_PATCH_OP_UNSUPPORTED;
                }
                throw new PatchRejectException(code, scopeError);
            }
            // 版本核对：意图基于 baseRevision，服务端与当前版本核对（并发竞争由 T2 条件归档最终仲裁）
            if (intent.getBaseRevision() != baseRevision) {
                throw new PatchRejectException(ItineraryPatchEngine.ERR_REVISION_CONFLICT,
                        "版本已变化：意图基于 v" + intent.getBaseRevision() + "，当前 v" + baseRevision);
            }

            // 3. 新候选召回与候选证据验证（REPLACE_PLACE 用旧计划之外的地点时）
            Map<String, Long> resolvedPlaceIds = new HashMap<>();
            Map<Long, String> resolvedNames = new HashMap<>();
            List<String> recallKeys = null;
            for (PatchOperation op : intent.getOperations()) {
                if (!PatchOperation.OP_REPLACE_PLACE.equals(op.getOp())) {
                    continue;
                }
                ItineraryPatchEngine.LocatedNode located = ItineraryPatchEngine.findNode(original, op.getNodeId());
                String currentKey = located == null ? null
                        : PlaceKeyResolver.fromNode(located.node())
                                .map(k -> CandidateService.placeKey(k.type().name(), k.id()))
                                .orElse(null);
                if (op.getPlaceKey().equals(currentKey)) {
                    continue; // 原地替换（等价地点）：无需召回
                }
                PlaceKey key = parsePlaceKey(op.getPlaceKey());
                if (key == null) {
                    throw new PatchRejectException(ItineraryPatchEngine.ERR_PATCH_CANDIDATE_NOT_IN_POOL,
                            "地点键格式非法：" + op.getPlaceKey());
                }
                if (PlaceType.ATTRACTION.equals(key.type())) {
                    if (recallKeys == null) {
                        recallKeys = recallNewCandidates(sessionState);
                    }
                    if (!recallKeys.contains(op.getPlaceKey())) {
                        throw new PatchRejectException(ItineraryPatchEngine.ERR_PATCH_CANDIDATE_NOT_IN_POOL,
                                "替换地点不在召回候选池：" + op.getPlaceKey());
                    }
                }
                String name = resolvePlaceName(key);
                if (name == null) {
                    throw new PatchRejectException(ItineraryPatchEngine.ERR_PATCH_CANDIDATE_NOT_IN_POOL,
                            "替换地点不存在：" + op.getPlaceKey());
                }
                resolvedPlaceIds.put(op.getPlaceKey(), key.id());
                resolvedNames.put(key.id(), name);
            }

            // 4. 在副本上应用（旧版不动）；未触及节点语义比较（地点/活动/时间/交通/费用/住宿/备注/稳定身份）
            ItineraryPlan patched = objectMapper.readValue(
                    objectMapper.writeValueAsString(original), ItineraryPlan.class);
            List<String> touched = ItineraryPatchEngine.applyOperations(patched,
                    intent.getOperations(), resolvedPlaceIds, resolvedNames);
            List<String> mismatches = ItineraryPatchEngine.compareUntouched(original, patched, touched);
            if (!mismatches.isEmpty()) {
                throw new PatchRejectException(ItineraryPatchEngine.ERR_PATCH_UNTOUCHED_CHANGED,
                        "补丁改变了范围外节点：" + String.join("、", mismatches));
            }

            // 5. 整份计划复验：结构 → 账单（预算唯一入口）→ 发布检查
            validatePatchedPlan(patched, adjState, sessionState);

            adjState.setPlan(patched);
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("targetDays", intent.getTargetDays() == null ? List.of() : intent.getTargetDays());
            evidence.put("baseRevision", baseRevision);
            evidence.put("recallKeys", recallKeys == null ? List.of() : recallKeys);
            evidence.put("touchedNodeIds", touched);
            evidence.put("operations", intent.getOperations().stream()
                    .map(op -> {
                        Map<String, Object> o = new LinkedHashMap<>();
                        o.put("op", op.getOp());
                        o.put("nodeId", op.getNodeId());
                        o.put("placeKey", op.getPlaceKey() == null ? "" : op.getPlaceKey());
                        return o;
                    }).toList());
            return evidence;
        } catch (PatchRejectException e) {
            obsPatchEvent(null, adjState, false, "REJECTED",
                    Map.of("errorCode", String.valueOf(e.getCode())));
            throw e;
        } catch (Exception e) {
            log.warn("[Itinerary] 补丁调整执行失败: {}", e.getMessage(), e);
            throw new PatchRejectException("PATCH_EXECUTION_FAILED",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** 模型补丁提案：调用 + 解析 + 用量/轨迹记录；提供方异常按 PATCH_EXECUTION_FAILED 拒绝，不静默降级 */
    private AdjustPatchIntent proposePatch(TravelState state, String patchContext) {
        TraceContext ctx = traceService.newTrace(state.getSessionId(), "行程补丁");
        long startNs = System.nanoTime();
        try {
            dev.langchain4j.service.Result<String> r = patchAgent.propose(patchContext);
            long cost = (System.nanoTime() - startNs) / 1_000_000;
            ctx.add(AgentTrace.success("ItineraryPatchAgent", cost, r.tokenUsage()));
            ctx.finish("SUCCESS");
            obsPatchEvent(null, state, true, "PROPOSED", Map.of());
            usageService.recordAgent(auditSessionId(state), state.getUserId(), state.getUsername(),
                    "ADJUST", "行程补丁", "ItineraryPatchAgent", r.tokenUsage(), cost, "SUCCESS", null,
                    patchModel, UsageChannel.AGENT, UsageService.clip(patchContext, 2000));
            return JsonUtils.parse(r.content(), AdjustPatchIntent.class);
        } catch (Exception e) {
            long cost = (System.nanoTime() - startNs) / 1_000_000;
            ctx.add(AgentTrace.failure("ItineraryPatchAgent", cost,
                    e.getClass().getName() + ": " + e.getMessage()));
            ctx.finish("FAILED");
            usageService.recordAgent(auditSessionId(state), state.getUserId(), state.getUsername(),
                    "ADJUST", "行程补丁", "ItineraryPatchAgent", null, cost, "FAILED",
                    e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()),
                    patchModel, UsageChannel.RULE_FALLBACK, null);
            throw new PatchRejectException("PATCH_EXECUTION_FAILED",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            traceService.finish(ctx);
        }
    }

    /** 补丁上下文：只给当前行程（稳定身份 + 语义字段）、版本与诉求；模型不得接触 owner/operation/任意 JSON 路径 */
    private String buildPatchContext(ItineraryPlan plan, String adjustContext, int baseRevision) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("baseRevision", baseRevision);
        ctx.put("userMessage", adjustContext == null ? "" : adjustContext);
        ctx.put("plan", plan.getDays().stream().map(d -> {
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("dayIndex", d.getDayIndex());
            day.put("nodes", d.getNodes() == null ? List.of() : d.getNodes().stream().map(n -> {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("nodeId", n.getNodeId() == null ? "" : n.getNodeId());
                node.put("type", n.getType() == null ? "" : n.getType());
                node.put("placeId", n.getPlaceId() == null ? 0L : n.getPlaceId());
                node.put("name", n.getName() == null ? "" : n.getName());
                node.put("time", n.getTime() == null ? "" : n.getTime());
                node.put("note", n.getNote() == null ? "" : n.getNote());
                return node;
            }).toList());
            return day;
        }).toList());
        try {
            return objectMapper.writeValueAsString(ctx);
        } catch (Exception e) {
            throw new PatchRejectException("PATCH_EXECUTION_FAILED", e.getMessage());
        }
    }

    /** S09 新候选召回：步骤 1 同口径（硬过滤 + 全量扫描，禁忌/事实/预算同源） */
    private List<String> recallNewCandidates(TravelState sessionState) {
        if (candidateService == null) {
            throw new PatchRejectException(ItineraryPatchEngine.ERR_PATCH_CANDIDATE_NOT_IN_POOL,
                    "候选召回不可用");
        }
        return candidateService.recallAttractions(sessionState, 8).orderedKeys();
    }

    private PlaceKey parsePlaceKey(String placeKey) {
        int sep = placeKey.indexOf(':');
        if (sep <= 0 || sep == placeKey.length() - 1) {
            return null;
        }
        try {
            PlaceType type = PlaceType.valueOf(placeKey.substring(0, sep));
            return PlaceKey.of(type, Long.parseLong(placeKey.substring(sep + 1)));
        } catch (Exception e) {
            return null;
        }
    }

    private String resolvePlaceName(PlaceKey key) {
        return switch (key.type()) {
            case ATTRACTION -> {
                Attraction a = attractionMapper.selectById(key.id());
                yield a == null ? null : a.getName();
            }
            case RESTAURANT -> {
                Restaurant r = restaurantMapper.selectById(key.id());
                yield r == null ? null : r.getName();
            }
            case HOTEL -> {
                Hotel h = hotelMapper.selectById(key.id());
                yield h == null ? null : h.getName();
            }
        };
    }

    /**
     * S09 整份计划复验：结构 → 账单（预算唯一入口）→ 发布检查。
     * 不注入/不重排/不重算时间——未触及节点必须保持不变，由调用方先做语义比较。
     */
    private void validatePatchedPlan(ItineraryPlan patched, TravelState adjState, TravelState sessionState) {
        int expectedDays = adjState.getPreference().getDays() == null
                ? patched.getDays().size() : adjState.getPreference().getDays();
        List<String> structure = PlanStructureValidator.validate(patched, expectedDays);
        if (!structure.isEmpty()) {
            throw new PatchRejectException(ItineraryPatchEngine.ERR_PATCH_VALIDATION_FAILED,
                    "结构校验未通过：" + String.join("；", structure));
        }
        Set<Long> attrIds = new HashSet<>();
        Set<Long> foodIds = new HashSet<>();
        Set<Long> hotelIds = new HashSet<>();
        for (DailyPlan d : patched.getDays()) {
            for (PlanNode n : d.getNodes()) {
                if (n.getPlaceId() == null) {
                    continue;
                }
                switch (n.getType() == null ? "" : n.getType()) {
                    case "attraction", "rest" -> attrIds.add(n.getPlaceId());
                    case "restaurant" -> foodIds.add(n.getPlaceId());
                    case "hotel" -> hotelIds.add(n.getPlaceId());
                    default -> { }
                }
            }
        }
        List<Attraction> attractions = attrIds.isEmpty() ? List.of() : attractionMapper.selectBatchIds(attrIds);
        List<Restaurant> restaurants = foodIds.isEmpty() ? List.of() : restaurantMapper.selectBatchIds(foodIds);
        List<Hotel> hotels = hotelIds.isEmpty() ? List.of() : hotelMapper.selectBatchIds(hotelIds);
        Map<PlaceKey, double[]> coords = PlaceIndex.coords(attractions, restaurants, hotels, List.of());

        int party = adjState.getPreference().getPeopleCount() == null ? 2 : adjState.getPreference().getPeopleCount();
        Hotel hotel = hotels.isEmpty() ? null : hotels.get(0);
        List<StayBooking> stays = TripBilling.defaultStays(expectedDays, hotel, TripBilling.defaultRooms(party));
        BudgetBreakdown bill = TripBilling.calculateTrip(patched, stays, party,
                priceSnapshot(attractions, restaurants, hotels, List.of()), coords,
                adjState.getPreference().getTotalBudget());
        if (bill.isOverLimit()) {
            // 跨日全局预算：局部合法不等于整份可行，失败不覆盖旧版本
            throw new PatchRejectException(ItineraryPatchEngine.ERR_GLOBAL_BUDGET_EXCEEDED,
                    "调整后全程费用超过预算：" + bill.getKnownSubtotal() + " > " + bill.getTotalBudget());
        }

        Map<PlaceKey, String> openTimes = new HashMap<>();
        attractions.forEach(a -> openTimes.put(PlaceKey.of(PlaceType.ATTRACTION, a.getId()), a.getOpenTime()));
        Map<Long, Attraction> attById = attractions.stream().collect(Collectors.toMap(Attraction::getId, a -> a));
        boolean hardFatigueOverload = "偏弱".equals(adjState.getPreference().getEnergyLevel())
                && overloadedAfterRest(patched, attById);
        ItineraryValidator.ValidationResult check = ItineraryValidator.validate(patched, adjState.getPreference(),
                sessionState == null ? null : sessionState.getRequirementSnapshot(),
                adjState.getPreference().getTotalBudget(), bill.getKnownSubtotal(),
                openTimes, attById, hardFatigueOverload);
        if (!check.publishable() || check.needsConfirmation()) {
            throw new PatchRejectException(ItineraryPatchEngine.ERR_PATCH_VALIDATION_FAILED,
                    "调整后发布检查未通过：" + String.join("；", check.violations())
                            + (check.needsConfirmation() ? "；存在无法自动核实的硬条件" : ""));
        }
    }

    /** 候选休息点：低强度（≤2）景点，休闲类优先 */
    private List<Attraction> loadRestSpots(TravelState state) {
        List<Attraction> all = attractionMapper.selectList(new LambdaQueryWrapper<Attraction>()
                .eq(Attraction::getDestinationId, state.getDestinationId())
                .eq(Attraction::getStatus, 1)
                .le(Attraction::getIntensity, 2));
        List<Long> selectedIds = state.getSelectedAttractionIds();
        Comparator<Attraction> cmp = Comparator
                .comparingInt((Attraction a) -> "休闲".equals(a.getCategory()) ? 0 : 1)
                .thenComparing(Attraction::getRating, Comparator.reverseOrder());
        return all.stream()
                .filter(a -> selectedIds == null || !selectedIds.contains(a.getId()))
                .sorted(cmp)
                .limit(3)
                .toList();
    }

    private String buildRules(TravelState state, List<Attraction> restSpots) {
        int days = state.getPreference().getDays() == null ? 3 : state.getPreference().getDays();
        String restNames = restSpots.stream().map(Attraction::getName).collect(Collectors.joining("、"));
        StringBuilder sb = new StringBuilder();
        sb.append("- 共 ").append(days).append(" 天；首日以 transport 节点启程（抵达），末日以 transport 节点返程。\n");
        if (Boolean.TRUE.equals(state.getNoHotelNeeded())) {
            sb.append("- 用户不需要酒店：不要安排 hotel 节点，住宿费用按 0 计；每天以当日首个景点为起点和终点。\n");
        } else {
            sb.append("- 每天以酒店为起点和终点。\n");
        }
        if (Boolean.TRUE.equals(state.getNoAttractionNeeded())) {
            sb.append("- 用户不需要景点：不要安排 attraction 节点；行程以餐饮（和酒店）为主。\n");
        } else if (state.getSelectedAttractionIds() != null && !state.getSelectedAttractionIds().isEmpty()) {
            sb.append("- 用户已确认的景点必须全部安排进行程（缺一不可）；只有受硬约束（开放时间、劳累度上限）确实无法安排时才能少排，且必须在当天的 theme 或相应 note 中写明原因，不得静默遗漏。\n");
        }
        if (Boolean.TRUE.equals(state.getNoFoodNeeded())) {
            sb.append("- 用户不需要美食推荐：不要安排 restaurant 节点，用餐由用户自行解决、不产生餐费。\n");
        } else {
            sb.append("- 每天 11:30-13:30 之间安排 1 个 restaurant 节点（午餐），17:30-19:30 之间安排 1 个 restaurant 节点（晚餐）；车程中不安排用餐。\n");
        }
        if (restSpots.isEmpty()) {
            sb.append("- 高强度景点与低强度景点错开安排；无候选休息点，不插入 rest 节点。\n");
        } else {
            sb.append("- 高强度景点与低强度景点错开安排；如某天安排较满，可插入 1 个 rest 节点（候选休息点：")
                    .append(restNames).append("）。\n");
        }
        sb.append("- 全程预算约 ").append(state.getPreference().getTotalBudget()).append(" 元，")
                .append(state.getPreference().getPeopleCount() == null ? 2 : state.getPreference().getPeopleCount())
                .append(" 人：餐费按人均价×人数计算，餐饮+门票+交通合计不得超过预算；超预算时优先选择人均价更低的餐厅。");
        if (state.getPreference().getSpecialRequests() != null
                && !state.getPreference().getSpecialRequests().isBlank()) {
            sb.append("\n- 用户特殊需求：「").append(state.getPreference().getSpecialRequests())
                    .append("」，编排行程时尽量满足。");
        }
        if (state.getExtraRequest() != null && !state.getExtraRequest().isBlank()) {
            sb.append("\n- 用户在选点阶段额外提出：「").append(state.getExtraRequest())
                    .append("」，安排行程时尽量兼顾。");
        }
        if (state.getAdjustContext() != null && !state.getAdjustContext().isBlank()) {
            sb.append("\n\n【用户调整诉求与原行程】\n").append(state.getAdjustContext())
                    .append("\n请在原行程基础上做局部调整（其余天数保持不变或仅微调），并同样遵守以上规则。")
                    .append("如果调整诉求改变的是时间窗口（如返程时间推迟或提前），必须综合重排当天的节点时间与停留时长：")
                    .append("把多出的时间合理分配给各节点（延长停留、更从容的用餐），并优先补回此前未安排的已选景点；不要只改动一个节点。");
        }
        return sb.toString();
    }

    // ==================== 后处理（硬约束兜底） ====================

    private void postProcess(TravelState state, ItineraryPlan plan,
                             List<Attraction> attractions, List<Restaurant> restaurants,
                             List<Hotel> hotels, List<Attraction> restSpots,
                             Map<PlaceKey, double[]> coords,
                             com.ghy.mutiagent.service.route.RouteFactSnapshot facts) {
        Map<Long, Attraction> attById = attractions.stream().collect(Collectors.toMap(Attraction::getId, a -> a));
        Map<Long, Restaurant> foodById = restaurants.stream().collect(Collectors.toMap(Restaurant::getId, r -> r));
        Map<Long, Hotel> hotelById = hotels.stream().collect(Collectors.toMap(Hotel::getId, h -> h));
        Map<Long, Attraction> restById = restSpots.stream().collect(Collectors.toMap(Attraction::getId, a -> a));

        // 1. 白名单校验 + 名称回填（非法节点剔除）
        for (DailyPlan d : plan.getDays()) {
            List<PlanNode> valid = new ArrayList<>();
            for (PlanNode n : d.getNodes() == null ? List.<PlanNode>of() : d.getNodes()) {
                if (n.getType() == null) {
                    continue;
                }
                switch (n.getType()) {
                    case "transport" -> {
                        n.setName(n.getName() == null ? "交通节点" : n.getName());
                        valid.add(n);
                    }
                    case "hotel" -> {
                        if (n.getPlaceId() != null && hotelById.containsKey(n.getPlaceId())) {
                            n.setName(hotelById.get(n.getPlaceId()).getName());
                            valid.add(n);
                        }
                    }
                    case "attraction" -> {
                        if (n.getPlaceId() != null && attById.containsKey(n.getPlaceId())) {
                            n.setName(attById.get(n.getPlaceId()).getName());
                            valid.add(n);
                        }
                    }
                    case "restaurant" -> {
                        if (n.getPlaceId() != null && foodById.containsKey(n.getPlaceId())) {
                            n.setName(foodById.get(n.getPlaceId()).getName());
                            valid.add(n);
                        }
                    }
                    case "rest" -> {
                        if (n.getPlaceId() != null && restById.containsKey(n.getPlaceId())) {
                            n.setName(restById.get(n.getPlaceId()).getName());
                            valid.add(n);
                        }
                    }
                    default -> { }
                }
            }
            d.setNodes(valid);
            // 2. 饭点兜底：缺午餐/晚餐 → 插入最近顺路的已选饭店
            injectMeals(d, restaurants, coords);
        }

        // 3. 劳累度预判：需要休息则先插入（时间后续统一重算）
        double[] base = new double[plan.getDays().size()];
        for (int i = 0; i < plan.getDays().size(); i++) {
            base[i] = baseScore(plan.getDays().get(i), attById, state.getPreference().getEnergyLevel());
        }
        for (int i = 0; i < plan.getDays().size(); i++) {
            double prev = i > 0 ? base[i - 1] : 0;
            double next = i + 1 < base.length ? base[i + 1] : 0;
            if (FatigueScorer.needsRest(FatigueScorer.combined(prev, base[i], next))
                    && !hasRestNode(plan.getDays().get(i)) && !restSpots.isEmpty()) {
                injectRest(plan.getDays().get(i), restSpots.get(0));
            }
        }

        // 3.5 跨天去重：同一景点/餐厅/休息点全行程只出现一次（LLM 输出与自动补点都可能重复；
        // 酒店是每天驻地、交通节点是行程骨架，不受此限；同一天内可重复）
        dedupAcrossDays(plan, attById, foodById, restById, attractions, restaurants, restSpots);

        // 4. 时间轴确定性重算：通勤 + 停留时长 + 饭点锚定（LLM 时间仅作顺序参考；S10 起通勤走共享路线事实）
        for (DailyPlan d : plan.getDays()) {
            // 返程锚点：重算前留存 LLM 给出的返程时间（用户「X点回去」诉求由规划/调整 Agent 落在此节点），
            // 确定性重算会覆盖全部时间，需在重算后按锚点对齐
            PlanNode lastBefore = d.getNodes().isEmpty() ? null : d.getNodes().get(d.getNodes().size() - 1);
            String returnAnchor = lastBefore != null && "transport".equals(lastBefore.getType())
                    ? lastBefore.getTime() : null;
            ScheduleBuilder.schedule(d, coords, attById, facts)
                    .forEach(w -> log.warn("[Itinerary] 第{}天时间告警：{}", d.getDayIndex(), w));
            anchorReturnTime(d, returnAnchor);
            List<String> missing = MealTimeChecker.missingWindows(d.getNodes());
            if (!missing.isEmpty()) {
                log.warn("[Itinerary] 第{}天重算后仍缺饭点：{}", d.getDayIndex(), String.join("、", missing));
            }
        }

        // 5. 劳累分终算（含休息点与真实通勤）+ 按时间排序 + 重编号 + 算消费
        double[] finalBase = new double[plan.getDays().size()];
        for (int i = 0; i < plan.getDays().size(); i++) {
            finalBase[i] = baseScore(plan.getDays().get(i), attById, state.getPreference().getEnergyLevel());
        }
        int people = state.getPreference().getPeopleCount() == null ? 2 : state.getPreference().getPeopleCount();
        for (int i = 0; i < plan.getDays().size(); i++) {
            DailyPlan d = plan.getDays().get(i);
            double prev = i > 0 ? finalBase[i - 1] : 0;
            double next = i + 1 < finalBase.length ? finalBase[i + 1] : 0;
            d.setFatigueScore(Math.round(FatigueScorer.combined(prev, finalBase[i], next) * 10) / 10.0);
            d.getNodes().sort(Comparator.comparing(n -> n.getTime() == null ? "99:99" : n.getTime()));
            int seq = 1;
            for (PlanNode n : d.getNodes()) {
                n.setSeq(seq++);
            }
            d.setEstimatedCost(BudgetCalculator.dayCost(d.getNodes(), people, attById, foodById, hotelById, coords));
        }

        // S09：稳定节点身份——缺失 nodeId 的节点按天内顺序确定性补齐（此后不随排序/重编号变化）
        assignNodeIds(plan);
    }

    /**
     * 返程时间锚点：把末节点（返程 transport）对齐到 LLM 明确给出的时间（用户「X点回去」诉求的落点）。
     * 只允许比确定性重算更晚（更早的诉求涉及删减节点，另走调整流程），且不晚于 22:00 上限。
     */
    private static void anchorReturnTime(DailyPlan d, String returnAnchor) {
        if (returnAnchor == null || d.getNodes() == null || d.getNodes().isEmpty()) {
            return;
        }
        PlanNode last = d.getNodes().get(d.getNodes().size() - 1);
        if (!"transport".equals(last.getType()) || last.getTime() == null) {
            return;
        }
        int anchor;
        int computed;
        try {
            anchor = toMin(returnAnchor);
            computed = toMin(last.getTime());
        } catch (RuntimeException e) {
            return; // 非法时间不采用锚点
        }
        anchor = Math.min(anchor, 22 * 60);
        if (anchor <= computed) {
            return;
        }
        Integer travel = last.getTravelMinutes();
        last.setTime(toHm(anchor));
        if (travel != null) {
            last.setDepartTime(toHm(Math.max(0, anchor - travel)));
        }
    }

    private static int toMin(String hm) {
        String[] p = hm.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    private static String toHm(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    /** S09：为缺失 nodeId 的节点按天内顺序确定性补齐稳定身份（同类节点按出现位置 1..n 编号） */
    public static void assignNodeIds(ItineraryPlan plan) {
        if (plan == null || plan.getDays() == null) {
            return;
        }
        for (DailyPlan d : plan.getDays()) {
            if (d.getNodes() == null) {
                continue;
            }
            Map<String, Integer> ordinals = new HashMap<>();
            for (PlanNode n : d.getNodes()) {
                String type = n.getType() == null ? "x" : n.getType();
                int ordinal = ordinals.merge(type, 1, Integer::sum);
                if (n.getNodeId() == null || n.getNodeId().isBlank()) {
                    n.setNodeId(PlanNodeRef.ref(d.getDayIndex(), type, ordinal));
                }
            }
        }
    }

    /** 当天基础劳累分：Σ(强度×时长)×体力系数 + 通勤时长×1.5（通勤按重算后的 travelMinutes 累计） */
    private double baseScore(DailyPlan d, Map<Long, Attraction> attById, String energy) {
        return FatigueScorer.dayScore(d, attById, energy);
    }

    /** S04：明确轻松限制下，注入一次休息后仍有某天综合分超阈值 → 硬疲劳违规 */
    private boolean overloadedAfterRest(ItineraryPlan plan, Map<Long, Attraction> attById) {
        double[] base = new double[plan.getDays().size()];
        for (int i = 0; i < base.length; i++) {
            base[i] = FatigueScorer.dayScore(plan.getDays().get(i), attById, "偏弱");
        }
        for (int i = 0; i < base.length; i++) {
            double prev = i > 0 ? base[i - 1] : 0;
            double next = i + 1 < base.length ? base[i + 1] : 0;
            if (FatigueScorer.needsRest(FatigueScorer.combined(prev, base[i], next))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRestNode(DailyPlan d) {
        return d.getNodes().stream().anyMatch(n -> "rest".equals(n.getType()));
    }

    /** 疲劳硬违规的面向用户文案：给出最满一天的分值与可操作的调整建议 */
    private String fatigueAbortMessage(ItineraryPlan draft, List<Attraction> attractions,
                                       List<Attraction> restSpots) {
        return String.format("行程强度超出你的体力水平（偏弱）：最满的一天疲劳分约 %.1f（安全值 9），"
                + "自动修复无法在不移除你选定景点的前提下降低强度，暂未发布。"
                + "请减少 1 个景点，或把体力改为「一般」后重新生成。",
                maxFatigueDayScore(draft, attractions, restSpots));
    }

    /** 最满一天的疲劳分（与 S04 发布检查同口径，偏弱系数 1.3） */
    private double maxFatigueDayScore(ItineraryPlan plan, List<Attraction> attractions,
                                      List<Attraction> restSpots) {
        Map<Long, Attraction> attById = attractions.stream().collect(Collectors.toMap(Attraction::getId, a -> a));
        restSpots.forEach(a -> attById.put(a.getId(), a));
        double max = 0;
        for (DailyPlan d : plan.getDays()) {
            max = Math.max(max, FatigueScorer.dayScore(d, attById, "偏弱"));
        }
        return max;
    }

    /**
     * 确定性预算修复（BUDGET_EXCEEDED）：用「未入行程的更便宜已选餐厅」替换行程中最贵的餐厅，
     * 直至已核实费用不超过预算。只动用户已勾选的餐厅，不引入池外地点。返回修复后是否已在预算内。
     */
    private boolean budgetSwap(ItineraryPlan draft, List<Restaurant> restaurants, TravelPreference preference) {
        BudgetBreakdown bill = draft.getBudgetBreakdown();
        if (bill == null || bill.getKnownSubtotal() == null) {
            return true;
        }
        BigDecimal budget = bill.getTotalBudget() == null ? preference.getTotalBudget() : bill.getTotalBudget();
        if (budget == null) {
            return true;
        }
        BigDecimal known = bill.getKnownSubtotal();
        if (known.compareTo(budget) <= 0) {
            return true;
        }
        int people = preference.getPeopleCount() == null ? 2 : preference.getPeopleCount();
        Map<Long, Restaurant> byId = restaurants.stream().collect(Collectors.toMap(Restaurant::getId, r -> r));
        // 可替换来源：已选但未入行程的餐厅
        Set<Long> unused = new HashSet<>(byId.keySet());
        for (DailyPlan d : draft.getDays()) {
            for (PlanNode n : d.getNodes() == null ? List.<PlanNode>of() : d.getNodes()) {
                if ("restaurant".equals(n.getType()) && n.getPlaceId() != null) {
                    unused.remove(n.getPlaceId());
                }
            }
        }
        while (known.compareTo(budget) > 0) {
            PlanNode priciest = null;
            Restaurant priciestR = null;
            for (DailyPlan d : draft.getDays()) {
                for (PlanNode n : d.getNodes() == null ? List.<PlanNode>of() : d.getNodes()) {
                    if ("restaurant".equals(n.getType()) && n.getPlaceId() != null) {
                        Restaurant r = byId.get(n.getPlaceId());
                        if (r != null && r.getAvgPrice() != null
                                && (priciestR == null || r.getAvgPrice().compareTo(priciestR.getAvgPrice()) > 0)) {
                            priciest = n;
                            priciestR = r;
                        }
                    }
                }
            }
            if (priciest == null) {
                break;
            }
            final Restaurant target = priciestR;
            Restaurant cheaper = unused.stream().map(byId::get)
                    .filter(r -> r != null && r.getAvgPrice() != null
                            && r.getAvgPrice().compareTo(target.getAvgPrice()) < 0)
                    .min(Comparator.comparing(Restaurant::getAvgPrice))
                    .orElse(null);
            if (cheaper == null) {
                break;
            }
            known = known.subtract(priciestR.getAvgPrice().multiply(BigDecimal.valueOf(people)))
                    .add(cheaper.getAvgPrice().multiply(BigDecimal.valueOf(people)));
            priciest.setPlaceId(cheaper.getId());
            priciest.setName(cheaper.getName());
            unused.remove(cheaper.getId());
        }
        return known.compareTo(budget) <= 0;
    }

    /** 预算超支金额（已核实费用 − 预算；仅在已判定超预算的草稿上调用） */
    private BigDecimal budgetOverAmount(ItineraryPlan draft) {
        BudgetBreakdown bill = draft.getBudgetBreakdown();
        if (bill == null || bill.getKnownSubtotal() == null || bill.getTotalBudget() == null) {
            return BigDecimal.ZERO;
        }
        return bill.getKnownSubtotal().subtract(bill.getTotalBudget());
    }

    /** 缺饭点：在窗口默认时间插入离前序节点最近的已选饭店；返程早于 19:30 跳过晚餐、晚到跳过午餐 */
    private void injectMeals(DailyPlan d, List<Restaurant> foods, Map<PlaceKey, double[]> coords) {
        if (foods.isEmpty()) {
            return;
        }
        // 抵达/返程按当天首/末节点识别（编排约定：启程 transport 在首、返程 transport 在末）；
        // 不能拿任意 transport 判断——1 天行程同时含抵达与返程，任意匹配会把返程误判为「晚到」、把抵达误判为「早返程」
        List<PlanNode> ns = d.getNodes();
        PlanNode first = ns.isEmpty() ? null : ns.get(0);
        PlanNode last = ns.isEmpty() ? null : ns.get(ns.size() - 1);
        boolean arriveLate = first != null && "transport".equals(first.getType())
                && first.getTime() != null && first.getTime().compareTo("12:30") >= 0;
        // 返程早于晚餐窗口结束（19:30）说明来不及用晚餐；21:30 这类晚返程必须保留晚餐
        boolean departBeforeDinner = last != null && "transport".equals(last.getType())
                && last.getTime() != null && last.getTime().compareTo(MealTimeChecker.DINNER_TO) < 0;

        for (String window : MealTimeChecker.missingWindows(d.getNodes())) {
            if ("午餐".equals(window) && arriveLate) {
                continue;
            }
            if ("晚餐".equals(window) && departBeforeDinner) {
                continue;
            }
            Restaurant nearest = nearestRestaurant(d, foods, coords);
            if (nearest == null) {
                continue;
            }
            PlanNode meal = new PlanNode();
            meal.setType("restaurant");
            meal.setPlaceId(nearest.getId());
            meal.setName(nearest.getName());
            meal.setTime(MealTimeChecker.defaultMealTime(window));
            meal.setNote(window + "（自动补齐）");
            d.getNodes().add(meal);
            // S04：补餐必须插到窗口内的正确位置（如午餐在晚间节点之前），不能一律追加在队尾
            d.getNodes().sort(Comparator.comparing(n -> n.getTime() == null ? "99:99" : n.getTime()));
        }
    }

    private Restaurant nearestRestaurant(DailyPlan d, List<Restaurant> foods, Map<PlaceKey, double[]> coords) {
        PlanNode anchor = d.getNodes().isEmpty() ? null : d.getNodes().get(0);
        double[] ac = anchor == null ? null
                : PlaceKeyResolver.fromNode(anchor).map(coords::get).orElse(null);
        if (ac == null) {
            return foods.get(0);
        }
        return foods.stream()
                .min(Comparator.comparingDouble(r -> GeoUtils.distanceKm(ac[0], ac[1], r.getLng(), r.getLat())))
                .orElse(null);
    }

    private void injectRest(DailyPlan d, Attraction restSpot) {
        PlanNode rest = new PlanNode();
        rest.setType("rest");
        rest.setPlaceId(restSpot.getId());
        rest.setName(restSpot.getName());
        rest.setTime("15:30");
        rest.setNote("休息点（缓解疲劳）");
        d.getNodes().add(rest);
        // S04：休息点插入导致疲劳的活动之间，按时间落位；每天最多自动补一次（调用方 hasRestNode 保证）
        d.getNodes().sort(Comparator.comparing(n -> n.getTime() == null ? "99:99" : n.getTime()));
    }

    /**
     * 跨天去重（硬约束兜底，不依赖 LLM 自觉）：同一景点/餐厅/休息点全行程只出现一次。
     * 酒店是每天驻地、交通节点是行程骨架，不受此限；同一天内允许重复。
     * 备选处理：先替换为池中未用地点；无备选时休息点直接移除（软性节点），
     * 餐厅（饭点硬约束）与景点（避免行程过空）保留并告警。
     */
    static void dedupAcrossDays(ItineraryPlan plan,
                                Map<Long, Attraction> attById, Map<Long, Restaurant> foodById,
                                Map<Long, Attraction> restById, List<Attraction> attractions,
                                List<Restaurant> restaurants, List<Attraction> restSpots) {
        // 键为 type:id：景点/餐厅/休息点分属不同表，id 会撞车，必须按类型区分
        Set<String> used = new HashSet<>();
        for (DailyPlan d : plan.getDays()) {
            if (d.getNodes() == null) {
                continue;
            }
            List<PlanNode> kept = new ArrayList<>();
            for (PlanNode n : d.getNodes()) {
                Long pid = n.getPlaceId();
                if (pid == null || "transport".equals(n.getType()) || "hotel".equals(n.getType())) {
                    kept.add(n);
                    continue;
                }
                String key = n.getType() + ":" + pid;
                if (used.add(key)) {
                    kept.add(n);
                    continue;
                }
                String oldName = n.getName();
                Long alt = replacementFor(n.getType(), used, attractions, restaurants, restSpots);
                if (alt != null) {
                    n.setPlaceId(alt);
                    switch (n.getType()) {
                        case "attraction" -> n.setName(attById.get(alt).getName());
                        case "restaurant" -> n.setName(foodById.get(alt).getName());
                        case "rest" -> n.setName(restById.get(alt).getName());
                        default -> { }
                    }
                    used.add(n.getType() + ":" + alt);
                    kept.add(n);
                    log.warn("[Itinerary] 第{}天「{}」与前序天重复，已替换为「{}」",
                            d.getDayIndex(), oldName, n.getName());
                } else if ("rest".equals(n.getType())) {
                    log.warn("[Itinerary] 第{}天休息点「{}」跨天重复且无备选休息点，已移除",
                            d.getDayIndex(), oldName);
                } else {
                    kept.add(n);
                    log.warn("[Itinerary] 第{}天「{}」跨天重复且无备选（餐厅为饭点硬约束/景点保底），保留",
                            d.getDayIndex(), oldName);
                }
            }
            d.setNodes(kept);
        }
    }

    /** 去重备选：同类型池中第一个未被使用的 id；没有则返回 null */
    private static Long replacementFor(String type, Set<String> used,
                                       List<Attraction> attractions, List<Restaurant> restaurants,
                                       List<Attraction> restSpots) {
        switch (type) {
            case "attraction" -> {
                for (Attraction a : attractions) {
                    if (!used.contains("attraction:" + a.getId())) {
                        return a.getId();
                    }
                }
            }
            case "restaurant" -> {
                for (Restaurant r : restaurants) {
                    if (!used.contains("restaurant:" + r.getId())) {
                        return r.getId();
                    }
                }
            }
            case "rest" -> {
                for (Attraction r : restSpots) {
                    if (!used.contains("rest:" + r.getId())) {
                        return r.getId();
                    }
                }
            }
            default -> { }
        }
        return null;
    }

    // ==================== 文本渲染与落库 ====================

    /** S06-A：落库失败必须向上传播（insert 行数≠1 / 主键为空 / 存储异常一律不允许吞掉） */
    private void persist(TravelState state, ItineraryPlan plan) {
        ItineraryCommitService.CommitResult r = commitService.commitNew(
                new ItineraryCommitService.CommitNew(buildItineraryRow(state, plan, objectMapper)));
        state.setItineraryId(r.newId());
        obsCommitResult(null, state, "COMMITTED", Map.of("itineraryId", r.newId()));
    }

    /** B：发布用户知情确认的疲劳超载草稿（渲染行程文本 + 落库 itinerary 行 + 回填 itineraryId） */
    public void publishPending(TravelState state) {
        ItineraryPlan draft = state.getPendingPlan();
        if (draft == null) {
            throw new BizException(ResultCode.STATE_CONFLICT);
        }
        state.setPlan(draft);
        state.setItineraryText(ItineraryTextRenderer.render(draft));
        persist(state, draft);
    }

    /** 构造行程行（不写库）：commitNew / commitAdjustment / 操作协议 T2 共用 */
    public static Itinerary buildItineraryRow(TravelState state, ItineraryPlan plan,
                                              ObjectMapper objectMapper) {
        try {
            Itinerary it = new Itinerary();
            it.setUserId(state.getUserId() == null ? 0L : state.getUserId());
            it.setDestinationId(state.getDestinationId());
            int days = state.getPreference().getDays() == null ? plan.getDays().size() : state.getPreference().getDays();
            it.setTitle(state.getDestinationName() + days + "天" + Math.max(0, days - 1) + "晚攻略");
            it.setDays(days);
            it.setTotalBudget(state.getPreference().getTotalBudget());
            it.setTotalCost(ItineraryTextRenderer.totalCost(plan));
            it.setPreferenceJson(objectMapper.writeValueAsString(state.getPreference()));
            it.setPlanJson(objectMapper.writeValueAsString(plan));
            it.setStatus("ACTIVE");
            it.setVersion(1);
            return it;
        } catch (Exception e) {
            log.error("行程行构造失败（序列化异常）: {}", e.getMessage());
            throw new BizException(ResultCode.EXECUTE_ERROR);
        }
    }

    // ==================== 目的地 / 行程查询 / 调整 / 评价 ====================

    /** 可选目的地列表（知识库中的城市） */
    public List<Destination> destinations() {
        return destinationMapper.selectList(new LambdaQueryWrapper<Destination>()
                .orderByAsc(Destination::getId));
    }

    /** 当前用户的全部有效行程（列表页）；uid 必须来自登录态，不允许匿名回退 */
    public List<ItinerarySummary> listItineraries(long userId) {
        List<Itinerary> list = itineraryMapper.selectList(new LambdaQueryWrapper<Itinerary>()
                .eq(Itinerary::getUserId, userId)
                .eq(Itinerary::getStatus, "ACTIVE")
                .orderByDesc(Itinerary::getCreatedAt));
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, String> destNames = destinationMapper.selectList(null).stream()
                .collect(Collectors.toMap(Destination::getId, Destination::getName, (a, b) -> a));
        List<ItinerarySummary> result = new ArrayList<>();
        for (Itinerary it : list) {
            ItinerarySummary s = new ItinerarySummary();
            s.setId(it.getId());
            s.setDestinationId(it.getDestinationId());
            s.setDestinationName(destNames.get(it.getDestinationId()));
            s.setTitle(it.getTitle());
            s.setDays(it.getDays());
            s.setTotalCost(it.getTotalCost());
            s.setVersion(it.getVersion());
            s.setStatus(it.getStatus());
            s.setCreatedAt(it.getCreatedAt());
            result.add(s);
        }
        return result;
    }

    /** 归属校验：不存在或不属于当前用户统一按资源不可见处理（404），禁止匿名探测 */
    private Itinerary requireOwnedItinerary(Long itineraryId, AuthenticatedUser actor) {
        Itinerary it = itineraryMapper.selectById(itineraryId);
        if (it == null || it.getUserId() == null || !it.getUserId().equals(actor.id())) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        return it;
    }

    /** 行程详情：偏好 + 结构化行程 + 渲染文本（仅所有者可见） */
    public ItineraryDetail detail(AuthenticatedUser actor, Long itineraryId) {
        Itinerary it = requireOwnedItinerary(itineraryId, actor);
        try {
            Destination dest = destinationMapper.selectById(it.getDestinationId());
            ItineraryDetail d = new ItineraryDetail();
            d.setId(it.getId());
            d.setDestinationId(it.getDestinationId());
            d.setDestinationName(dest == null ? null : dest.getName());
            d.setTitle(it.getTitle());
            d.setDays(it.getDays());
            d.setTotalBudget(it.getTotalBudget());
            d.setTotalCost(it.getTotalCost());
            d.setStatus(it.getStatus());
            d.setVersion(it.getVersion());
            d.setCreatedAt(it.getCreatedAt());
            d.setPreference(objectMapper.readValue(it.getPreferenceJson(), TravelPreference.class));
            ItineraryPlan plan = objectMapper.readValue(it.getPlanJson(), ItineraryPlan.class);
            // S09：旧数据缺失稳定身份的节点读取时确定性补齐（展示与后续补丁同口径）
            assignNodeIds(plan);
            d.setPlan(plan);
            d.setText(ItineraryTextRenderer.render(plan));
            return d;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[Itinerary] 详情解析失败: {}", e.getMessage());
            throw new BizException(ResultCode.PARAM_ERROR);
        }
    }

    /** S08 修复引擎装配（生产由 Spring 装配；门禁桥接按用例注入预算/时钟/脚本提供方） */
    @Autowired(required = false)
    public void setRepairEngine(ItineraryRepairEngine repairEngine) {
        this.repairEngine = repairEngine;
    }

    /** S09 补丁 Agent 装配（可选；未装配时不提供局部补丁调整） */
    @Autowired(required = false)
    public void setPatchAgent(com.ghy.mutiagent.agent.ItineraryPatchAgent patchAgent) {
        this.patchAgent = patchAgent;
    }

    /** S09 候选服务装配（可选；REPLACE_PLACE 新候选召回依赖） */
    @Autowired(required = false)
    public void setCandidateService(CandidateService candidateService) {
        this.candidateService = candidateService;
    }

    /** S10 路线事实服务（可选装配）：装配后一次生成内的排程/账单共享同一路线事实快照 */
    private com.ghy.mutiagent.service.route.RouteFactService routeFactService;

    @Autowired(required = false)
    public void setRouteFactService(com.ghy.mutiagent.service.route.RouteFactService routeFactService) {
        this.routeFactService = routeFactService;
    }

    /** S06-B：事务外规划（模型 + 规则 + 校验，不写库），草案由操作协议持久化 */
    public Map<String, Object> plan(TravelState state) {
        return generateInternal(state, false, null, null);
    }

    /** S11：携带操作取消令牌的规划——在途执行线程在 调用前/验证前/修复前/提交前 复查取消信号 */
    public Map<String, Object> plan(TravelState state, CancelRegistry.CancelToken cancelToken) {
        return generateInternal(state, false, cancelToken, null);
    }

    /** S12：携带操作追踪元数据的规划——provider attempt span 挂接 operationId/版本/快照 */
    public Map<String, Object> plan(TravelState state, CancelRegistry.CancelToken cancelToken,
                                    com.ghy.mutiagent.trace.TraceMeta meta) {
        return generateInternal(state, false, cancelToken, meta);
    }

    /** 调整规划上下文（归属校验 + adjustContext + 地点池取自原行程），返回旧行与待规划状态 */
    public record AdjustPlan(Itinerary oldRow, TravelState state) {
    }

    public AdjustPlan prepareAdjust(AuthenticatedUser actor, Long itineraryId, String message,
                                    String parentSessionId) {
        if (message == null || message.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR);
        }
        // 归属优先：不存在/不属于当前用户按 404 处理，避免匿名探测
        Itinerary old = requireOwnedItinerary(itineraryId, actor);
        if (old.getPlanJson() == null) {
            throw new BizException(ResultCode.PARAM_ERROR);
        }
        if (!"ACTIVE".equals(old.getStatus())) {
            throw new BizException(ResultCode.STATE_CONFLICT);
        }
        try {
            TravelPreference pref = objectMapper.readValue(old.getPreferenceJson(), TravelPreference.class);
            ItineraryPlan original = objectMapper.readValue(old.getPlanJson(), ItineraryPlan.class);
            // S09：旧计划缺少稳定身份的节点，读取时按确定性口径补齐（不随重排变化）
            assignNodeIds(original);
            Destination dest = destinationMapper.selectById(old.getDestinationId());

            TravelState st = new TravelState();
            st.setSessionId("adj-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
            // 审计归并：调整子任务的用量记录计入父会话维度（trace 仍按 adj-* 隔离）
            st.setParentSessionId(parentSessionId);
            st.setUserId(actor.id());
            st.setUsername(actor.username());
            st.setDestinationId(old.getDestinationId());
            st.setDestinationName(dest == null ? "" : dest.getName());
            st.setPreference(pref);
            st.setAdjustContext(objectMapper.writeValueAsString(original)
                    + "\n\n用户调整诉求：" + message);

            Set<Long> att = new HashSet<>();
            Set<Long> food = new HashSet<>();
            Set<Long> hotel = new HashSet<>();
            for (DailyPlan d : original.getDays()) {
                for (PlanNode n : d.getNodes()) {
                    if (n.getPlaceId() == null) {
                        continue;
                    }
                    switch (n.getType() == null ? "" : n.getType()) {
                        case "attraction", "rest" -> att.add(n.getPlaceId());
                        case "restaurant" -> food.add(n.getPlaceId());
                        case "hotel" -> hotel.add(n.getPlaceId());
                        default -> { }
                    }
                }
            }
            st.setSelectedAttractionIds(new ArrayList<>(att));
            st.setSelectedFoodIds(new ArrayList<>(food));
            st.setSelectedHotelIds(new ArrayList<>(hotel));
            return new AdjustPlan(old, st);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[Itinerary] 调整上下文构建失败: {}", e.getMessage());
            throw new BizException(ResultCode.EXECUTE_ERROR);
        }
    }

    /** 兼容旧入口（无会话上下文时父会话为空，用量记录仍落在 adj-* 子任务维度） */
    public ItineraryDetail adjust(AuthenticatedUser actor, Long itineraryId, String message) {
        return adjust(actor, itineraryId, message, null);
    }

    /**
     * 基于已有行程生成调整版本：地点池取自原行程，
     * 原行程 JSON + 用户诉求作为 adjustContext 注入规划 Agent。
     * 新版本存为新行（version+1、parentId 指向旧行），旧行归档。
     * parentSessionId 为发起调整的会话 ID（审计归并用）。
     */
    public ItineraryDetail adjust(AuthenticatedUser actor, Long itineraryId, String message,
                                  String parentSessionId) {
        AdjustPlan ap = prepareAdjust(actor, itineraryId, message, parentSessionId);
        try {
            // S06-A：事务外规划（模型 + 规则 + 校验），事务内短提交（新行 + 条件归档旧行）
            plan(ap.state());
            int expectedVersion = ap.oldRow().getVersion() == null ? 1 : ap.oldRow().getVersion();
            ItineraryCommitService.CommitResult r = commitService.commitAdjustment(
                    new ItineraryCommitService.CommitAdjustment(ap.oldRow().getId(), expectedVersion,
                            buildItineraryRow(ap.state(), ap.state().getPlan(), objectMapper)));
            usageService.recordOp(auditSessionId(ap.state()), actor.id(), actor.username(), "ADJUST", "调整行程",
                    "SUCCESS", trim50(message), UsageChannel.OP);
            log.info("[Itinerary] 行程 {} 调整完成，新版本 {}", itineraryId, r.newId());
            return detail(actor, r.newId());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[Itinerary] 行程调整失败: {}", e.getMessage());
            throw new BizException(ResultCode.EXECUTE_ERROR);
        }
    }

    /** 用量审计维度：派生状态（调整子任务）计入父会话，其余用自身会话 ID */
    private String auditSessionId(TravelState state) {
        return state.getParentSessionId() == null ? state.getSessionId() : state.getParentSessionId();
    }

    /** 提交行程评价反馈，落库 t_itinerary_feedback（仅所有者可评价） */
    /**
     * S12 反馈幂等提交：同 owner + feedbackKey 唯一，网络重试返回同条评价（不重复插入）；
     * 绑定确切行程 revision；复核状态初始 PENDING，不得直接进入评测集或在线权重更新。
     */
    public ItineraryFeedback submitFeedbackWithKey(AuthenticatedUser actor, Long itineraryId,
                                                   int expectedRevision, String feedbackKey,
                                                   Integer rating, List<String> tags) {
        Itinerary it = requireOwnedItinerary(itineraryId, actor);
        if (it.getVersion() == null || it.getVersion() != expectedRevision) {
            throw new BizException(ResultCode.STATE_CONFLICT.getCode(),
                    "评价目标行程版本已变化，请刷新后重新提交");
        }
        if (feedbackKey == null || feedbackKey.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR);
        }
        ItineraryFeedback existing = itineraryFeedbackMapper.findByKey(actor.id(), feedbackKey);
        if (existing != null) {
            return existing;
        }
        ItineraryFeedback f = new ItineraryFeedback();
        f.setItineraryId(itineraryId);
        f.setUserId(actor.id());
        f.setFeedbackKey(feedbackKey);
        f.setItineraryRevision(expectedRevision);
        f.setRating(rating);
        f.setTags(tags == null || tags.isEmpty() ? null : String.join(",", tags));
        f.setReviewStatus("PENDING");
        itineraryFeedbackMapper.insert(f);
        usageService.recordOp("itin-" + itineraryId, actor.id(), actor.username(), "FEEDBACK", "提交评价",
                "SUCCESS", "评分 " + (rating == null ? "-" : rating) + " 分", UsageChannel.OP);
        return f;
    }

    public void submitFeedback(AuthenticatedUser actor, Long itineraryId, FeedbackRequest req) {
        requireOwnedItinerary(itineraryId, actor);
        ItineraryFeedback f = new ItineraryFeedback();
        f.setItineraryId(itineraryId);
        f.setUserId(actor.id());
        f.setRating(req.getRating());
        f.setPaceRating(req.getPaceRating());
        f.setAttractionSatisfy(req.getAttractionSatisfy());
        f.setFoodSatisfy(req.getFoodSatisfy());
        f.setHotelSatisfy(req.getHotelSatisfy());
        f.setBudgetFit(req.getBudgetFit());
        f.setTags(req.getTags() == null || req.getTags().isEmpty() ? null : String.join(",", req.getTags()));
        f.setComment(req.getComment());
        itineraryFeedbackMapper.insert(f);
        usageService.recordOp("itin-" + itineraryId, actor.id(), actor.username(), "FEEDBACK", "提交评价",
                "SUCCESS", "评分 " + (req.getRating() == null ? "-" : req.getRating()) + " 分",
                UsageChannel.OP);
    }

    private static int[] tokenCounts(TokenUsage u) {
        return u == null ? new int[]{0, 0}
                : new int[]{u.inputTokenCount() == null ? 0 : u.inputTokenCount(),
                            u.outputTokenCount() == null ? 0 : u.outputTokenCount()};
    }

    private String trim50(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 50 ? s : s.substring(0, 50) + "…";
    }

    /** 已选实体的价格快照（S05）：缺价格返回 null，不得默认免费 */
    private PriceSnapshot priceSnapshot(List<Attraction> attractions, List<Restaurant> restaurants,
                                        List<Hotel> hotels, List<Attraction> restSpots) {
        Map<PlaceKey, BigDecimal> prices = new HashMap<>();
        attractions.forEach(a -> prices.put(PlaceKey.of(PlaceType.ATTRACTION, a.getId()), a.getTicketPrice()));
        restSpots.forEach(a -> prices.put(PlaceKey.of(PlaceType.ATTRACTION, a.getId()), a.getTicketPrice()));
        restaurants.forEach(r -> prices.put(PlaceKey.of(PlaceType.RESTAURANT, r.getId()), r.getAvgPrice()));
        hotels.forEach(h -> prices.put(PlaceKey.of(PlaceType.HOTEL, h.getId()), h.getPricePerNight()));
        return new PriceSnapshot(prices);
    }

    // ==================== 规则兜底（LLM 完全失败时） ====================

    private ItineraryPlan fallbackPlan(TravelState state, List<Attraction> attractions,
                                       List<Restaurant> restaurants, List<Hotel> hotels,
                                       Map<PlaceKey, Double> scoreById) {
        int days = state.getPreference().getDays() == null ? 3 : state.getPreference().getDays();
        Hotel hotel = hotels.isEmpty() ? null : hotels.get(0);

        List<Attraction> sorted = attractions.stream()
                .sorted(Comparator.<Attraction>comparingDouble(
                                a -> scoreById.getOrDefault(PlaceKey.of(PlaceType.ATTRACTION, a.getId()), 0.0)).reversed()
                        .thenComparingInt(Attraction::getIntensity))
                .toList();
        List<List<Attraction>> buckets = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            buckets.add(new ArrayList<>());
        }
        for (int i = 0; i < sorted.size(); i++) {
            buckets.get(i % days).add(sorted.get(i));
        }

        ItineraryPlan plan = new ItineraryPlan();
        List<DailyPlan> dlist = new ArrayList<>();
        for (int d = 0; d < days; d++) {
            DailyPlan dp = new DailyPlan();
            dp.setDayIndex(d + 1);
            dp.setTheme(d == 0 ? "抵达与初步游玩" : (d == days - 1 ? "返程日" : "第" + (d + 1) + "天游玩"));
            List<PlanNode> nodes = new ArrayList<>();
            if (d == 0) {
                nodes.add(node("transport", null, "09:00", "启程抵达"));
            }
            if (hotel != null) {
                nodes.add(node("hotel", hotel.getId(), d == 0 ? "10:30" : "09:00", "酒店"));
            }
            if (!restaurants.isEmpty()) {
                nodes.add(node("restaurant", restaurants.get(0).getId(), "12:00", "午餐"));
            }
            // 每天上限 2 个景点（按评分序取前 2）：1 天内景点过多会把晚餐/休息点挤出 22:00 日终上限，
            // 触发饭点/日终校验无法收敛；兜底路径优先保证行程可发布（其余已选景点让位）
            String[] times = {"14:00", "15:30"};
            int t = 0;
            for (Attraction a : buckets.get(d).stream().limit(2).toList()) {
                nodes.add(node("attraction", a.getId(), times[Math.min(t++, times.length - 1)], "游玩"));
            }
            if (d == days - 1) {
                if (!restaurants.isEmpty()) {
                    // 返程当天排晚餐（时间轴重算会锚定到 17:30-19:30 窗口）；占位时间单调递增，
                    // 保证后续按时间排序不会把返程插到晚餐前面
                    nodes.add(node("restaurant",
                            restaurants.get(Math.min(1, restaurants.size() - 1)).getId(), "18:30", "晚餐"));
                }
                nodes.add(node("transport", null, "21:00", "返程"));
            } else {
                if (restaurants.size() > 1) {
                    nodes.add(node("restaurant", restaurants.get(1).getId(), "18:00", "晚餐"));
                }
                if (hotel != null) {
                    nodes.add(node("hotel", hotel.getId(), "20:30", "休息"));
                }
            }
            dp.setNodes(nodes);
            dlist.add(dp);
        }
        plan.setDays(dlist);
        return plan;
    }

    private PlanNode node(String type, Long placeId, String time, String note) {
        PlanNode n = new PlanNode();
        n.setType(type);
        n.setPlaceId(placeId);
        n.setTime(time);
        n.setNote(note);
        return n;
    }
}
