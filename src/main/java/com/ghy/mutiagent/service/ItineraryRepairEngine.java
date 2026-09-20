package com.ghy.mutiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.agent.ItineraryRepairAgent;
import com.ghy.mutiagent.common.BizException;
import com.ghy.mutiagent.common.OpAbortException;
import com.ghy.mutiagent.common.ResultCode;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.rule.PlanNodeRef;
import com.ghy.mutiagent.service.validation.ItineraryValidator;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * S08 有限修复引擎：共享预算的创建与提供方调用包装（Planner/Repair 同预算、同截止）、
 * 违规统一与修复上下文构造。真正的「验证 → 修复 → 再验证」状态机在 ItineraryService 编排。
 */
public class ItineraryRepairEngine {

    /** 修复上下文/终止语义使用的统一违规：code 为机器可读码，nodeIds 为 PlanNodeRef 节点引用 */
    public record RepairViolation(String code, int day, List<String> nodeIds, boolean repairable) {
    }

    private final ItineraryRepairAgent repairAgent;
    private final ItineraryRepairAgent repairAgentHeavy;
    private final ObjectMapper objectMapper;
    private final TimeSource time;
    private final OperationBudgetConfig config;

    /** 兼容构造：快慢修复共用同一 Agent（门禁夹具等场景行为与旧版一致） */
    public ItineraryRepairEngine(ItineraryRepairAgent repairAgent, ObjectMapper objectMapper,
                                 TimeSource time, OperationBudgetConfig config) {
        this(repairAgent, repairAgent, objectMapper, time, config);
    }

    public ItineraryRepairEngine(ItineraryRepairAgent repairAgent, ItineraryRepairAgent repairAgentHeavy,
                                 ObjectMapper objectMapper, TimeSource time, OperationBudgetConfig config) {
        this.repairAgent = repairAgent;
        this.repairAgentHeavy = repairAgentHeavy;
        this.objectMapper = objectMapper;
        this.time = time;
        this.config = config;
    }

    public OperationBudgetConfig config() {
        return config;
    }

    /** 每个 operation 一个预算：deadline 从创建即锚定（执行起点） */
    public OperationBudget newBudget() {
        return new OperationBudget(time, config);
    }

    /** 保守输入 token 估计（混合文本，字符数/4 近似），用于调用前预留 */
    public static long estimateTokens(String text) {
        return text == null || text.isEmpty() ? 0 : (text.length() + 3) / 4;
    }

    private static Long totalTokens(TokenUsage usage) {
        if (usage == null) {
            return null;
        }
        Integer in = usage.inputTokenCount();
        Integer out = usage.outputTokenCount();
        if (in == null && out == null) {
            return null;
        }
        return (long) (in == null ? 0 : in) + (out == null ? 0 : out);
    }

    /**
     * 规划调用包装：原子预留 → 调用 → 实际结算。
     * 预留失败（截止/配额/token）→ 以终止语义抛出，不发请求、不兜底。
     * 调用异常 → 未知用量挂账后原样上抛（是否按截止终止由调用方判断）。
     */
    public Result<String> plannerCall(OperationBudget budget, long estimatedInputTokens,
                                      Supplier<Result<String>> call) {
        OperationBudget.Reserve reserve = budget.reserveCall(estimatedInputTokens);
        if (reserve == null) {
            throw abortOf(budget);
        }
        try {
            Result<String> result = call.get();
            budget.settle(reserve, totalTokens(result.tokenUsage()));
            return result;
        } catch (Exception e) {
            budget.settle(reserve, null);
            throw e;
        }
    }

    /**
     * 修复调用包装：预留 → 调用 → 结算，语义同 plannerCall。
     * 返回原始 Result 供调用方取内容与用量；异常上抛（含 TOKEN_BUDGET_EXHAUSTED 终止信号）。
     */
    /** 修复调用包装：预留 → 调用 → 结算，语义同 plannerCall；heavy=true 时用强模型兜底。
     *  返回原始 Result 供调用方取内容与用量；异常上抛（含 TOKEN_BUDGET_EXHAUSTED 终止信号）。 */
    public Result<String> repair(OperationBudget budget, String repairContext, boolean heavy) {
        OperationBudget.Reserve reserve = budget.reserveCall(estimateTokens(repairContext));
        if (reserve == null) {
            throw abortOf(budget);
        }
        ItineraryRepairAgent agent = heavy ? repairAgentHeavy : repairAgent;
        try {
            Result<String> result = agent.repair(repairContext);
            budget.settle(reserve, totalTokens(result.tokenUsage()));
            return result;
        } catch (Exception e) {
            budget.settle(reserve, null);
            throw e;
        }
    }

    /** 预留失败终止：截止已过 → DEADLINE_EXCEEDED；否则按「待用户确认」终止（不静默重试） */
    private OpAbortException abortOf(OperationBudget budget) {
        List<String> reasons = budget.reasonCodes();
        if (reasons.contains(OperationBudget.REASON_DEADLINE)) {
            return new OpAbortException(ResultCode.DEADLINE_EXCEEDED, "DEADLINE_EXCEEDED", reasons,
                    List.of(), Map.of());
        }
        return new OpAbortException(ResultCode.PLAN_INVALID, "NEEDS_CONFIRMATION", reasons,
                List.of(), Map.of());
    }

    /**
     * 发布检查结果统一为修复违规：
     * OPENING_HOURS_CONFLICT → OPENING_HOURS_VIOLATION（修复语义码），其余沿用校验码；
     * 节点引用取自校验器的违规定位（locatedViolations）。
     */
    public static List<RepairViolation> unify(ItineraryValidator.ValidationResult check) {
        List<RepairViolation> out = new ArrayList<>();
        Map<String, List<String>> located = check.locatedViolations() == null
                ? Map.of() : check.locatedViolations();
        for (String code : check.violations()) {
            String unified = ItineraryValidator.OPENING_HOURS_CONFLICT.equals(code)
                    ? "OPENING_HOURS_VIOLATION" : code;
            List<String> nodes = located.getOrDefault(code, List.of());
            int day = nodes.isEmpty() ? 0 : PlanNodeRef.dayOf(nodes.get(0));
            // 疲劳超载的出路是用户调整（减少景点/改体力自报），模型修复无法在不移除用户选定景点的
            // 前提下降低疲劳分，且休息节点不改变疲劳分口径——快速失败，不消耗修复轮次
            boolean repairable = !ItineraryValidator.STRUCTURE_INVALID.equals(code)
                    && !ItineraryValidator.HARD_FATIGUE_EXCEEDED.equals(code);
            out.add(new RepairViolation(unified, day, nodes, repairable));
        }
        return out;
    }

    /**
     * 修复上下文：只给受影响计划、violations（含节点定位）、不可变条件与锁定节点白名单。
     * lockedNodeIds 在 S09 稳定 nodeId 落地后填充；提示词不是安全边界，输出仍必须重新过全局验证。
     */
    public String buildRepairContext(ItineraryPlan draft, List<RepairViolation> violations,
                                     TravelState state) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        List<Map<String, Object>> vs = new ArrayList<>();
        for (RepairViolation v : violations) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", v.code());
            item.put("day", v.day());
            item.put("targetNodeIds", v.nodeIds());
            vs.add(item);
        }
        ctx.put("violations", vs);
        ctx.put("lockedNodeIds", List.of());
        Map<String, Object> requirements = new LinkedHashMap<>();
        if (state.getPreference() != null) {
            requirements.put("days", state.getPreference().getDays());
            requirements.put("peopleCount", state.getPreference().getPeopleCount());
            requirements.put("totalBudget", state.getPreference().getTotalBudget());
            requirements.put("specialRequests", state.getPreference().getSpecialRequests());
        }
        // 用户已确认的景点/餐厅（修复时一个都不能移除，只能重排顺序/时间）
        requirements.put("selectedAttractionIds",
                state.getSelectedAttractionIds() == null ? List.of() : state.getSelectedAttractionIds());
        requirements.put("selectedFoodIds",
                state.getSelectedFoodIds() == null ? List.of() : state.getSelectedFoodIds());
        requirements.put("extraRequest", state.getExtraRequest());
        requirements.put("hardConstraints", hardConstraintSummary(state));
        ctx.put("requirements", requirements);
        ctx.put("plan", draft);
        try {
            return objectMapper.writeValueAsString(ctx);
        } catch (Exception e) {
            throw new BizException(ResultCode.EXECUTE_ERROR);
        }
    }

    /** 不可变硬条件摘要（key/value，不含内部状态） */
    private static List<Map<String, String>> hardConstraintSummary(TravelState state) {
        if (state == null || state.getRequirementSnapshot() == null
                || state.getRequirementSnapshot().getConstraints() == null) {
            return List.of();
        }
        List<Map<String, String>> out = new ArrayList<>();
        state.getRequirementSnapshot().getConstraints().stream()
                .filter(c -> "HARD".equals(c.getHardness()) && "ACTIVE".equals(c.getStatus()))
                .forEach(c -> out.add(Map.of("key", c.getKey(), "value",
                        c.getValue() == null ? "" : c.getValue())));
        return out;
    }
}
