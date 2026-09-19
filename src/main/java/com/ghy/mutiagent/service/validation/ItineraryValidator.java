package com.ghy.mutiagent.service.validation;

import com.ghy.mutiagent.model.ConstraintEntry;
import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.PlaceKey;
import com.ghy.mutiagent.model.PlanNode;
import com.ghy.mutiagent.model.RequirementSnapshot;
import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.repository.entity.Attraction;
import com.ghy.mutiagent.rule.MealTimeChecker;
import com.ghy.mutiagent.rule.OpeningHoursParser;
import com.ghy.mutiagent.rule.PlaceKeyResolver;
import com.ghy.mutiagent.rule.PlanNodeRef;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 发布检查器（S04）：`validate(draft, requirements, facts)` 返回结构化 ValidationResult。
 * publishable 由违规码统一计算，模型不能输出 valid=true 绕过。
 * 违规码（测试断言码而非中文句子）：
 * - TIME_OVERLAP：上一节点结束晚于下一节点出发；
 * - OPENING_HOURS_CONFLICT：已知开放时间无法覆盖整段游览；
 * - BUDGET_EXCEEDED：已知费用超过明确预算上限；
 * - MEAL_WINDOW_UNSATISFIABLE：有效时段内缺午餐/晚餐；
 * - DAY_END_EXCEEDED：每日活动结束超过 22:00 上限（含排过 24:00）；
 * - DEPARTURE_ORDER：返程 transport 之后仍有活动；
 * - HARD_CONSTRAINT_VIOLATED：明确硬限制被违反；
 * - HARD_FATIGUE_EXCEEDED：明确轻松限制下注入休息后仍超载；
 * - STRUCTURE_INVALID：结构违规（缺天/null 天/节点超上限）；
 * 硬限制无法判定 → needsConfirmation（待确认，不发布）。
 */
public final class ItineraryValidator {

    public static final String TIME_OVERLAP = "TIME_OVERLAP";
    public static final String OPENING_HOURS_CONFLICT = "OPENING_HOURS_CONFLICT";
    public static final String BUDGET_EXCEEDED = "BUDGET_EXCEEDED";
    public static final String MEAL_WINDOW_UNSATISFIABLE = "MEAL_WINDOW_UNSATISFIABLE";
    public static final String DAY_END_EXCEEDED = "DAY_END_EXCEEDED";
    public static final String DEPARTURE_ORDER = "DEPARTURE_ORDER";
    public static final String HARD_CONSTRAINT_VIOLATED = "HARD_CONSTRAINT_VIOLATED";
    public static final String HARD_FATIGUE_EXCEEDED = "HARD_FATIGUE_EXCEEDED";
    public static final String STRUCTURE_INVALID = "STRUCTURE_INVALID";

    /** 每日活动结束上限：22:00 */
    private static final int DAY_END_MIN = 22 * 60;
    private static final int DAY_MINUTES = 24 * 60;

    /**
     * 校验结果：locatedViolations 为违规码 → 受影响节点引用（PlanNodeRef，如 d2-a1），
     * 供 S08 修复输入定位受影响部分；无定位的违规（如缺饭点）不出现键。
     */
    public record ValidationResult(List<String> violations, List<String> warnings,
                                   boolean needsConfirmation, boolean publishable,
                                   Map<String, List<String>> locatedViolations) {
        public static ValidationResult publishable(List<String> warnings) {
            return new ValidationResult(List.of(), warnings, false, true, Map.of());
        }
    }

    private ItineraryValidator() {
    }

    /**
     * @param openTimes 地点 → 营业时间原文（无法解析视为 UNKNOWN，不虚构保证）
     * @param hardFatigueOverload 明确轻松限制下注入一次休息后仍超载（由调用方按 FatigueScorer 同口径计算）
     */
    public static ValidationResult validate(ItineraryPlan plan, TravelPreference preference,
                                            RequirementSnapshot snapshot, BigDecimal budget,
                                            BigDecimal knownSubtotal,
                                            Map<PlaceKey, String> openTimes,
                                            Map<Long, Attraction> attById,
                                            boolean hardFatigueOverload) {
        List<String> violations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, List<String>> located = new LinkedHashMap<>();
        boolean needsConfirmation = false;

        int expectedDays = preference == null || preference.getDays() == null ? 0 : preference.getDays();
        List<String> structure = PlanStructureValidator.validate(plan, expectedDays);
        if (!structure.isEmpty()) {
            violations.add(STRUCTURE_INVALID);
            return new ValidationResult(violations, warnings, false, false, located);
        }

        // 用户明确不需要美食（noFood 硬约束）：用餐由用户自行解决，不强制饭点窗口——否则与
        // 「不要 restaurant 节点」硬约束冲突，形成无法收敛的缺餐死锁
        boolean noFoodActive = activeHard(snapshot, "noFood");
        for (DailyPlan d : plan.getDays()) {
            timelineChecks(d, openTimes, violations, located);
            if (!noFoodActive) {
                List<String> missing = new ArrayList<>(MealTimeChecker.missingWindows(d.getNodes()));
                // 返程早于晚餐窗口结束（19:30）的当天不强制晚餐——与注入侧跳过口径一致，
                // 避免「来不及吃晚餐」的早返程行程陷入缺餐死锁
                missing.removeIf(m -> "晚餐".equals(m) && returnsBeforeDinner(d));
                if (!missing.isEmpty()) {
                    violations.add(MEAL_WINDOW_UNSATISFIABLE);
                }
            }
        }

        if (budget != null && knownSubtotal != null && knownSubtotal.compareTo(budget) > 0) {
            violations.add(BUDGET_EXCEEDED);
        }

        needsConfirmation = hardConstraintChecks(plan, snapshot, attById, violations);

        if (hardFatigueOverload) {
            violations.add(HARD_FATIGUE_EXCEEDED);
        }

        boolean publishable = violations.isEmpty() && !needsConfirmation;
        return new ValidationResult(violations, warnings, needsConfirmation, publishable, located);
    }

    /** 当天返程（末节点 transport）时间早于晚餐窗口结束 → 晚餐不强制 */
    private static boolean returnsBeforeDinner(DailyPlan d) {
        if (d.getNodes() == null || d.getNodes().isEmpty()) {
            return false;
        }
        PlanNode last = d.getNodes().get(d.getNodes().size() - 1);
        return "transport".equals(last.getType()) && last.getTime() != null
                && last.getTime().compareTo(MealTimeChecker.DINNER_TO) < 0;
    }

    /** 需求快照中是否存在 ACTIVE 的指定硬约束 */
    private static boolean activeHard(RequirementSnapshot snapshot, String key) {
        if (snapshot == null || snapshot.getConstraints() == null) {
            return false;
        }
        return snapshot.getConstraints().stream().anyMatch(c ->
                key.equals(c.getKey()) && "HARD".equals(c.getHardness()) && "ACTIVE".equals(c.getStatus()));
    }

    /** 时间轴检查：重叠、跨夜、结束上限、返程顺序、开放时间 */
    private static void timelineChecks(DailyPlan d, Map<PlaceKey, String> openTimes,
                                       List<String> violations, Map<String, List<String>> located) {
        List<PlanNode> nodes = d.getNodes();
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        List<String> refs = PlanNodeRef.refs(nodes, d.getDayIndex());
        int prevEnd = -1;
        for (int i = 0; i < nodes.size(); i++) {
            PlanNode n = nodes.get(i);
            String ref = refs.get(i);
            // 返程 transport 只允许作为当天首/末节点；其后不允许再有活动
            if ("transport".equals(n.getType()) && i > 0 && i < nodes.size() - 1) {
                violations.add(DEPARTURE_ORDER);
                locate(located, DEPARTURE_ORDER, ref);
            }
            Integer arrival = toMin(n.getTime());
            Integer dur = n.getDurationMinutes();
            if (arrival == null || dur == null) {
                continue;
            }
            int end = arrival + dur;
            if (end > DAY_END_MIN) {
                violations.add(DAY_END_EXCEEDED);
                locate(located, DAY_END_EXCEEDED, ref);
            }
            if (end > DAY_MINUTES) {
                violations.add(DAY_END_EXCEEDED);
                locate(located, DAY_END_EXCEEDED, ref);
            }
            if (prevEnd >= 0 && arrival < prevEnd) {
                violations.add(TIME_OVERLAP);
                locate(located, TIME_OVERLAP, ref);
            }
            prevEnd = end;
            // 开放时间：整段游览完全处于一个开放区间
            if ("attraction".equals(n.getType()) || "rest".equals(n.getType())) {
                Optional<PlaceKey> key = PlaceKeyResolver.fromNode(n);
                String open = key.map(k -> openTimes == null ? null : openTimes.get(k)).orElse(null);
                if (open != null
                        && OpeningHoursParser.covers(open, arrival, end) == OpeningHoursParser.Status.CONFLICT) {
                    violations.add(OPENING_HOURS_CONFLICT);
                    locate(located, OPENING_HOURS_CONFLICT, ref);
                }
            }
        }
    }

    private static void locate(Map<String, List<String>> located, String code, String ref) {
        List<String> list = located.computeIfAbsent(code, k -> new ArrayList<>());
        if (!list.contains(ref)) {
            list.add(ref);
        }
    }

    /** 硬限制：违反 → HARD_CONSTRAINT_VIOLATED；无法判定 → needsConfirmation=true（不发布） */
    private static boolean hardConstraintChecks(ItineraryPlan plan, RequirementSnapshot snapshot,
                                                Map<Long, Attraction> attById, List<String> violations) {
        boolean needsConfirmation = false;
        if (snapshot == null || snapshot.getConstraints() == null) {
            return false;
        }
        for (ConstraintEntry c : snapshot.getConstraints()) {
            if (!"HARD".equals(c.getHardness()) || !"ACTIVE".equals(c.getStatus())) {
                continue;
            }
            switch (c.getKey()) {
                case "avoidClimbing" -> {
                    boolean verified = false;
                    for (DailyPlan d : plan.getDays()) {
                        for (PlanNode n : d.getNodes()) {
                            Optional<PlaceKey> key = PlaceKeyResolver.fromNode(n);
                            if (key.isEmpty() || !"attraction".equals(n.getType())) {
                                continue;
                            }
                            Attraction a = attById.get(key.get().id());
                            if (a == null) {
                                continue;
                            }
                            String category = a.getCategory();
                            if (category == null || category.isBlank()) {
                                needsConfirmation = true; // 数据未知：无法判定
                            } else {
                                verified = true;
                                if (category.contains("山") || category.contains("攀登")) {
                                    violations.add(HARD_CONSTRAINT_VIOLATED);
                                }
                            }
                        }
                    }
                    if (!verified && !needsConfirmation) {
                        needsConfirmation = true;
                    }
                }
                case "requireAccessible" -> {
                    // S07 无障碍事实：features 含「无障碍」= TRUE，缺失 = UNKNOWN（无法判定，待确认）
                    boolean verified = false;
                    for (DailyPlan d : plan.getDays()) {
                        for (PlanNode n : d.getNodes()) {
                            Optional<PlaceKey> key = PlaceKeyResolver.fromNode(n);
                            if (key.isEmpty() || !"attraction".equals(n.getType())) {
                                continue;
                            }
                            Attraction a = attById.get(key.get().id());
                            if (a == null) {
                                continue;
                            }
                            String features = a.getFeatures();
                            if (features == null || features.isBlank()) {
                                needsConfirmation = true; // 事实未知：无法判定
                            } else {
                                verified = true;
                                if (!features.contains("无障碍")) {
                                    violations.add(HARD_CONSTRAINT_VIOLATED);
                                }
                            }
                        }
                    }
                    if (!verified && !needsConfirmation) {
                        needsConfirmation = true;
                    }
                }
                case "noHotel" -> {
                    // 用户明确不需要酒店：行程中出现 hotel 节点即违反（确定性可判，无需外部事实）
                    for (DailyPlan d : plan.getDays()) {
                        for (PlanNode n : d.getNodes()) {
                            if ("hotel".equals(n.getType())) {
                                violations.add(HARD_CONSTRAINT_VIOLATED);
                            }
                        }
                    }
                }
                case "noFood" -> {
                    // 用户明确不需要美食推荐：行程中出现 restaurant 节点即违反
                    for (DailyPlan d : plan.getDays()) {
                        for (PlanNode n : d.getNodes()) {
                            if ("restaurant".equals(n.getType())) {
                                violations.add(HARD_CONSTRAINT_VIOLATED);
                            }
                        }
                    }
                }
                case "noAttraction" -> {
                    // 用户明确不需要景点：行程中出现 attraction 节点即违反
                    for (DailyPlan d : plan.getDays()) {
                        for (PlanNode n : d.getNodes()) {
                            if ("attraction".equals(n.getType())) {
                                violations.add(HARD_CONSTRAINT_VIOLATED);
                            }
                        }
                    }
                }
                default -> needsConfirmation = true; // 无验证器的硬限制：待确认
            }
        }
        return needsConfirmation;
    }

    private static Integer toMin(String hm) {
        if (hm == null) {
            return null;
        }
        String[] p = hm.split(":");
        if (p.length != 2) {
            return null;
        }
        try {
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
