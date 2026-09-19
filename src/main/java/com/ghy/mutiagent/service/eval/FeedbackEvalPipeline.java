package com.ghy.mutiagent.service.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * 反馈到离线报告闭环（手册 §6）：行程 → 反馈 → 人工复核 → 评测 → 报告。
 *
 * - 只有人工复核 APPROVED 的反馈才能进入评测（§3.2 复核、脱敏、版本化后加入）；
 * - 每条反馈转换为一个评测样本（sampleId 由 feedbackKey 派生），重复运行保留全部原始结果；
 * - 报告的每个引用必须能解析回已复核反馈，brokenReferences 列出悬空引用；
 * - 本链路只产生离线评测结果，不触发任何在线调参（在线权重变化由外部计数器观察）。
 */
public final class FeedbackEvalPipeline {

    private FeedbackEvalPipeline() {
    }

    /** 经人工复核的反馈：feedbackKey + 关联行程 + 评分 + 复核状态 */
    public record ReviewedFeedback(String feedbackKey, Long itineraryId, int rating,
                                   String reviewStatus) {
        public boolean approved() {
            return "APPROVED".equals(reviewStatus);
        }
    }

    /** 一次评测运行：runId + 样本 + 反馈引用 + 口径 + 原始结果 */
    public record EvalRun(String runId, String sampleId, String feedbackKey,
                          String providerMode, String rawResult, long atMs) {
    }

    /** 离线报告：复核反馈数、原始运行数、悬空引用、全部原始结果 */
    public record EvalReport(String providerMode, boolean productionQualityClaim,
                             int evaluatedFeedbackCount, int rawRunCount,
                             List<String> brokenReferences, List<EvalRun> rawRuns) {

        public EvalReport {
            brokenReferences = brokenReferences == null ? List.of() : List.copyOf(brokenReferences);
            rawRuns = rawRuns == null ? List.of() : List.copyOf(rawRuns);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("providerMode", providerMode);
            m.put("productionQualityClaim", productionQualityClaim);
            m.put("evaluatedFeedbackCount", evaluatedFeedbackCount);
            m.put("rawRunCount", rawRunCount);
            m.put("brokenReferences", brokenReferences);
            List<Map<String, Object>> refs = new ArrayList<>();
            for (EvalRun r : rawRuns) {
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("runId", r.runId());
                ref.put("sampleId", r.sampleId());
                ref.put("feedbackKey", r.feedbackKey());
                refs.add(ref);
            }
            m.put("rawRunRefs", refs);
            return m;
        }
    }

    /** 评测替身：返回某反馈样本某次重复的原始输出 */
    @FunctionalInterface
    public interface RawProvider {
        String produce(String sampleId, String feedbackKey, int repeatIndex);
    }

    /** 执行闭环：仅 APPROVED 反馈进入评测；runId/sampleId 与 feedbackKey 全程关联 */
    public static EvalReport evaluate(List<ReviewedFeedback> feedbacks, int repeats,
                                      String providerMode, String runIdPrefix,
                                      RawProvider provider) {
        List<ReviewedFeedback> approved = new ArrayList<>();
        if (feedbacks != null) {
            for (ReviewedFeedback fb : feedbacks) {
                if (fb != null && fb.approved()) {
                    approved.add(fb);
                }
            }
        }
        Set<String> approvedKeys = new LinkedHashSet<>();
        for (ReviewedFeedback fb : approved) {
            approvedKeys.add(fb.feedbackKey());
        }

        List<EvalRun> runs = new ArrayList<>();
        for (ReviewedFeedback fb : approved) {
            String sampleId = "sample-" + fb.feedbackKey();
            for (int r = 0; r < repeats; r++) {
                String runId = runIdPrefix + "-" + fb.feedbackKey() + "-r" + r;
                String raw = provider.produce(sampleId, fb.feedbackKey(), r);
                runs.add(new EvalRun(runId, sampleId, fb.feedbackKey(), providerMode, raw,
                        System.currentTimeMillis()));
            }
        }
        List<String> broken = validateReferences(runs, approvedKeys);
        return new EvalReport(providerMode, "LIVE".equals(providerMode),
                approved.size(), runs.size(), broken, runs);
    }

    /** 引用完整性：每条运行记录都必须能指回已复核的反馈，否则为悬空引用 */
    public static List<String> validateReferences(List<EvalRun> runs, Set<String> approvedFeedbackKeys) {
        List<String> broken = new ArrayList<>();
        if (runs == null) {
            return broken;
        }
        for (EvalRun run : runs) {
            if (run == null || !approvedFeedbackKeys.contains(run.feedbackKey())) {
                broken.add(run == null ? "null-run" : run.runId() + "->feedback:" + run.feedbackKey());
            }
        }
        return broken;
    }
}
