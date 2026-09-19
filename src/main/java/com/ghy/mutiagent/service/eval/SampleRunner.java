package com.ghy.mutiagent.service.eval;

import com.ghy.mutiagent.common.JsonUtils;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.service.validation.ItineraryValidator;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单样本执行器（手册 §3.1/§3.3）：判据快照（执行前）→ provider 产出计划 → 真实验证器评判（执行后判据快照）。
 *
 * - 工具白名单来自数据集 toolSnapshot 的地点键，白名单外的请求计 unauthorizedToolRequests；
 * - 计划用与线上同一套宽容解析（JsonUtils），评判用真实 ItineraryValidator（EvalJudge）；
 * - 判据配置全程只读：执行前后各导出一次深拷贝快照，任何执行路径（包括恶意提示样本）
 *   都无法改写 expected、工具白名单或评审提示词。
 */
public final class SampleRunner {

    /** provider 替身接口：只接收样本数据与工具白名单，永远接触不到判据配置 */
    @FunctionalInterface
    public interface PlanProvider {
        ProviderResponse respond(String sampleId, String inputText, List<String> toolWhitelist);
    }

    /** provider 响应：计划 JSON 原文 + 本次请求的工具列表（供白名单核对） */
    public record ProviderResponse(String planJson, List<String> requestedTools) {
        public ProviderResponse {
            requestedTools = requestedTools == null ? List.of() : List.copyOf(requestedTools);
        }
    }

    /** 评判事实装配：由调用方按固定工具事实/数据库行构造（费用、开放时间、景点属性、天数、禁选地点） */
    @FunctionalInterface
    public interface JudgeFacts {
        EvalJudge.JudgeInput build(ItineraryPlan plan, JudgeConfig config, DatasetSample sample);
    }

    /** 一次样本执行的原始观察；providerError 非空 = provider 抛异常（区别于解析/判据失败） */
    public record SampleExecution(Map<String, Object> judgeConfigBefore,
                                  Map<String, Object> judgeConfigAfter,
                                  int unauthorizedToolRequests,
                                  int committedHardViolationCount,
                                  boolean needsConfirmation,
                                  List<String> violations,
                                  String planJson,
                                  String providerError) {
    }

    private SampleRunner() {
    }

    public static SampleExecution run(DatasetSample sample, DatasetToolSnapshot toolSnapshot,
                                      JudgeConfig config, PlanProvider provider, JudgeFacts facts) {
        Map<String, Object> judgeConfigBefore = config.toMap();

        List<String> whitelist = toolSnapshot == null ? List.of() : toolSnapshot.placeKeys();
        ProviderResponse response;
        try {
            // 单样本 provider 调用独立 try/catch：provider 抛异常也有记录，不中断批次后续样本
            response = provider.respond(sample.id(), sample.input(), whitelist);
        } catch (Exception e) {
            return new SampleExecution(judgeConfigBefore, config.toMap(), 0, 0, false, List.of(),
                    null, e.getClass().getName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
        Set<String> allowed = new LinkedHashSet<>(whitelist);
        int unauthorizedToolRequests = 0;
        for (String tool : response.requestedTools()) {
            if (!allowed.contains(tool)) {
                unauthorizedToolRequests++;
            }
        }

        ItineraryPlan plan = null;
        if (response.planJson() != null) {
            try {
                plan = JsonUtils.parse(response.planJson(), ItineraryPlan.class);
            } catch (RuntimeException ignored) {
                plan = null;
            }
        }

        int committedHardViolationCount;
        boolean needsConfirmation;
        List<String> violations;
        if (plan == null) {
            committedHardViolationCount = 1;
            needsConfirmation = false;
            violations = List.of(ItineraryValidator.STRUCTURE_INVALID);
        } else {
            EvalJudge.JudgeOutcome outcome = EvalJudge.judge(facts.build(plan, config, sample));
            committedHardViolationCount = outcome.violations().size();
            needsConfirmation = outcome.needsConfirmation();
            violations = outcome.violations();
        }

        Map<String, Object> judgeConfigAfter = config.toMap();
        return new SampleExecution(judgeConfigBefore, judgeConfigAfter, unauthorizedToolRequests,
                committedHardViolationCount, needsConfirmation, violations, response.planJson(), null);
    }
}
