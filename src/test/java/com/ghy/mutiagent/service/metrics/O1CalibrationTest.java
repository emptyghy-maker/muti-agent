package com.ghy.mutiagent.service.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.service.eval.DatasetSample;
import com.ghy.mutiagent.service.eval.JudgeConfig;
import com.ghy.mutiagent.service.eval.PairedExperiment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O1 计量校准 12 项夹具（手册 O1 §4）：先修好仪表再付费调用模型。
 * T01~T07/T09 聚合口径（MetricsCalculator），T08/T11/T12 实验执行器（PairedExperiment），T10 判据隔离（JudgeConfig）。
 */
class O1CalibrationTest {

    private static MetricsCalculator.AttemptEvent attempt(String task, String op, String attemptId,
                                                          Long start, Long end,
                                                          Integer in, Integer out, boolean usageKnown,
                                                          String providerStatus, String validationStatus,
                                                          BigDecimal cost, boolean costKnown,
                                                          String error) {
        return new MetricsCalculator.AttemptEvent(task, op, attemptId, null, null, null,
                null, null, null, null, start, end, in, out, usageKnown,
                providerStatus, null, validationStatus, cost, costKnown, error);
    }

    private static DatasetSample sample(String id, String input) {
        return new DatasetSample(id, "g-" + id, "类别", "development", input,
                Map.of(), Map.of(), Map.of("hardViolationCount", 0),
                "fixture-1", "p-v1", "stub-v1", "synthetic");
    }

    @Test
    void o1T01_successfulTaskWithThreeCallsCostsFullyCounted() {
        List<MetricsCalculator.AttemptEvent> attempts = List.of(
                attempt("t1", "op1", "a1", 0L, 100L, 100, 50, true, "SUCCESS", "SUCCESS",
                        new BigDecimal("1.00"), true, null),
                attempt("t1", "op1", "a2", 100L, 200L, 100, 50, true, "SUCCESS", "SUCCESS",
                        new BigDecimal("2.00"), true, null),
                attempt("t1", "op1", "a3", 200L, 300L, 100, 50, true, "SUCCESS", "SUCCESS",
                        new BigDecimal("3.00"), true, null));
        List<MetricsCalculator.TaskAggregate> agg = MetricsCalculator.taskAggregates(attempts);
        assertEquals(1, agg.size());
        assertEquals(3, agg.get(0).attemptCount());
        assertEquals(300L, agg.get(0).wallMs());
        assertTrue(agg.get(0).success());

        MetricsCalculator.CostMetrics cm = MetricsCalculator.costsFromAttempts(attempts);
        assertEquals(new BigDecimal("6.00"), cm.totalKnownCost());
        assertEquals(1, cm.successfulPlanCount()); // 分母按任务，不是按 attempt
        assertEquals(new BigDecimal("6.00"), cm.costPerSuccessfulPlan());
        assertEquals(3, cm.totalAttempts());
    }

    @Test
    void o1T02_failedFirstAttemptKeptAndCostNotLost() {
        List<MetricsCalculator.AttemptEvent> attempts = List.of(
                attempt("t1", "op1", "a1", 0L, 50L, 10, 5, true, "FAILED", "FAILED",
                        new BigDecimal("1.00"), true, "boom"),
                attempt("t1", "op1", "a2", 50L, 150L, 20, 10, true, "SUCCESS", "SUCCESS",
                        new BigDecimal("2.00"), true, null));
        List<MetricsCalculator.TaskAggregate> agg = MetricsCalculator.taskAggregates(attempts);
        assertEquals(2, agg.get(0).attemptCount());
        assertEquals(1, agg.get(0).failedAttempts());
        assertEquals(new BigDecimal("3.00"), agg.get(0).knownCost()); // 失败尝试的费用不丢
        assertTrue(agg.get(0).success());

        MetricsCalculator.CostMetrics cm = MetricsCalculator.costsFromAttempts(attempts);
        assertEquals(2, cm.totalAttempts());
        assertEquals(new BigDecimal("3.00"), cm.totalKnownCost());
        assertEquals(1, cm.successfulPlanCount());
    }

    @Test
    void o1T03_replayedAttemptDoesNotDoubleCount() {
        List<MetricsCalculator.AttemptEvent> attempts = List.of(
                attempt("t1", "op1", "a1", 0L, 50L, 10, 5, true, "SUCCESS", "SUCCESS",
                        new BigDecimal("1.00"), true, null),
                attempt("t1", "op1", "a1", 0L, 50L, 10, 5, true, "SUCCESS", "SUCCESS",
                        new BigDecimal("1.00"), true, null), // 同 operation+attempt 重放
                attempt("t1", "op1", "a1", 0L, 50L, 10, 5, true, "SUCCESS", "SUCCESS",
                        new BigDecimal("1.00"), true, null),
                attempt("t1", "op1", "a2", 50L, 100L, 10, 5, true, "SUCCESS", "SUCCESS",
                        new BigDecimal("2.00"), true, null));
        List<MetricsCalculator.AttemptEvent> deduped = MetricsCalculator.dedupeAttempts(attempts);
        assertEquals(2, deduped.size());

        MetricsCalculator.CostMetrics cm = MetricsCalculator.costsFromAttempts(attempts);
        assertEquals(2, cm.totalAttempts());
        assertEquals(new BigDecimal("3.00"), cm.totalKnownCost()); // 重放不重复计费
        assertEquals(1, cm.successfulPlanCount());
    }

    @Test
    void o1T04_outOfOrderLogsUseAuthoritativeTerminalStatus() {
        // 乱序到达：COMMITTED(v3) 先到，RUNNING(v1) 后到
        List<MetricsCalculator.LogicalOperationEvent> events = List.of(
                new MetricsCalculator.LogicalOperationEvent("op1", "COMMITTED", false, 3),
                new MetricsCalculator.LogicalOperationEvent("op1", "RUNNING", false, 1));
        MetricsCalculator.OperationMetrics m = MetricsCalculator.operations(events);
        assertEquals(1, m.operationCount());
        assertEquals(1, m.successCount());

        // 同版本：终态 FAILED 胜过运行中 RUNNING
        List<MetricsCalculator.LogicalOperationEvent> tie = List.of(
                new MetricsCalculator.LogicalOperationEvent("op2", "RUNNING", false),
                new MetricsCalculator.LogicalOperationEvent("op2", "FAILED", false));
        assertEquals(0, MetricsCalculator.operations(tie).successCount());
        assertEquals(1, MetricsCalculator.operations(tie).operationCount());
    }

    @Test
    void o1T05_parentSpanNotCountedInProviderWorkload() {
        List<MetricsCalculator.SpanEvent> spans = List.of(
                new MetricsCalculator.SpanEvent("root", null, "OPERATION", 100L, 0L, 100L),
                new MetricsCalculator.SpanEvent("root-a1", "root", "PROVIDER", 40L, 10L, 50L),
                new MetricsCalculator.SpanEvent("root-a2", "root", "PROVIDER", 40L, 60L, 100L));
        assertEquals(80L, MetricsCalculator.providerWorkloadMs(spans)); // 父 span 不重复计
        assertEquals(100L, MetricsCalculator.rootWallMs(spans));
    }

    @Test
    void o1T06_parallelProvidersSumWorkRootWallIsRealSpan() {
        List<MetricsCalculator.SpanEvent> spans = List.of(
                new MetricsCalculator.SpanEvent("root", null, "OPERATION", 500L, 0L, 500L),
                new MetricsCalculator.SpanEvent("p1", "root", "PROVIDER", 100L, 0L, 100L),
                new MetricsCalculator.SpanEvent("p2", "root", "PROVIDER", 100L, 100L, 200L));
        assertEquals(200L, MetricsCalculator.providerWorkloadMs(spans)); // 并行工作量求和
        assertEquals(500L, MetricsCalculator.rootWallMs(spans)); // 墙钟按根真实起止
    }

    @Test
    void o1T07_timeoutWithMissingUsageIsUnknownNotNull() {
        List<MetricsCalculator.AttemptEvent> attempts = List.of(
                attempt("t1", "op1", "a1", 0L, 300000L, null, null, false, "FAILED", "FAILED",
                        null, false, "TimeoutException: 300s"));
        List<MetricsCalculator.TaskAggregate> agg = MetricsCalculator.taskAggregates(attempts);
        assertNull(agg.get(0).inputTokens()); // 缺失是 null，不是 0
        assertNull(agg.get(0).outputTokens());
        assertFalse(agg.get(0).usageComplete());

        MetricsCalculator.CostMetrics cm = MetricsCalculator.costsFromAttempts(attempts);
        assertEquals(1, cm.unknownCostCount());
        assertFalse(cm.costComplete());
        assertNull(cm.costPerSuccessfulPlan()); // 超时不是成功完成
    }

    @Test
    void o1T09_attemptTurnReportTokenTotalsAgree() {
        List<MetricsCalculator.AttemptEvent> attempts = List.of(
                attempt("t1", "op1", "a1", 0L, 100L, 100, 50, true, "SUCCESS", "SUCCESS",
                        null, false, null),
                attempt("t1", "op1", "a2", 100L, 200L, 200, 100, true, "SUCCESS", "SUCCESS",
                        null, false, null));
        MetricsCalculator.TaskAggregate agg = MetricsCalculator.taskAggregates(attempts).get(0);
        // attempt 层合计
        assertEquals(300, agg.inputTokens());
        assertEquals(150, agg.outputTokens());
        // turn 层（模拟回合级汇总）与报告层必须同口径
        int turnIn = 300;
        int turnOut = 150;
        assertEquals(turnIn, agg.inputTokens());
        assertEquals(turnOut, agg.outputTokens());
        assertEquals((long) turnIn + turnOut, (long) agg.inputTokens() + agg.outputTokens());
    }

    @Test
    void o1T10_providerCannotMutateNestedExpected() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("rules", new java.util.ArrayList<>(List.of("硬约束1", "硬约束2")));
        JudgeConfig config = new JudgeConfig("ds-1", nested, Map.of(), "human", "judge-v1");
        String hashBefore = sha256(config.expected());
        // 模拟 provider 拿到判据引用后改写嵌套集合
        @SuppressWarnings("unchecked")
        List<String> stolen = (List<String>) config.expected().get("rules");
        stolen.clear();
        stolen.add("被改写");
        String hashAfter = sha256(config.expected());
        assertEquals(hashBefore, hashAfter); // 判据副本未变
        assertEquals(List.of("硬约束1", "硬约束2"), config.expected().get("rules"));
    }

    @Test
    void o1T11_abbaRotationTraceableAndFactsConsistent() {
        List<DatasetSample> samples = List.of(sample("s1", "杭州两天"));
        PairedExperiment.ExperimentReport report = PairedExperiment.run(
                samples, 2, "baseline-v1", "candidate-v2", "hash-tools-1", "STUB",
                (version, sampleId, input, repeat) -> "RAW:" + version + ":" + sampleId + "#" + repeat);
        // AB/BA：repeat0 [baseline, candidate]，repeat1 [candidate, baseline]
        assertEquals("baseline-v1", report.rawResults().get(0).version());
        assertEquals("candidate-v2", report.rawResults().get(1).version());
        assertEquals("candidate-v2", report.rawResults().get(2).version());
        assertEquals("baseline-v1", report.rawResults().get(3).version());
        for (int i = 0; i < report.rawResults().size(); i++) {
            assertEquals(i, report.rawResults().get(i).sequenceIndex()); // 顺序可追溯
            assertEquals("hash-tools-1", report.rawResults().get(i).toolSnapshotHash()); // 事实一致
        }
    }

    @Test
    void o1T12_stubRowsNeverClaimLive() {
        PairedExperiment.ExperimentReport report = PairedExperiment.run(
                List.of(sample("s1", "杭州两天")), 1, "b", "c", "h", "STUB",
                (version, sampleId, input, repeat) -> "RAW");
        assertFalse(report.productionQualityClaim());
        for (PairedExperiment.RunRecord r : report.rawResults()) {
            assertEquals("STUB", r.providerMode()); // 逐行模式标记，不允许伪装 LIVE
        }
    }

    @Test
    void o1T08_providerFailureKeepsBothArmsAndSubsequentSamples() {
        List<DatasetSample> samples = List.of(sample("s1", "杭州两天"), sample("s2", "预算300"));
        PairedExperiment.ExperimentReport report = PairedExperiment.run(
                samples, 1, "baseline-v1", "candidate-v2", "h", "STUB",
                (version, sampleId, input, repeat) -> {
                    if ("baseline-v1".equals(version) && "s1".equals(sampleId)) {
                        throw new IllegalStateException("boom");
                    }
                    return "RAW";
                });
        assertEquals(4, report.totalRunCount()); // 两臂 + 后续样本全部有记录
        PairedExperiment.RunRecord failed = report.rawResults().get(0);
        assertEquals(PairedExperiment.STATUS_PROVIDER_FAILED, failed.status());
        assertTrue(failed.error().contains("boom"));
        assertEquals("RAW", report.rawResults().get(1).rawResult()); // 同样本 candidate 照常执行
        assertEquals("RAW", report.rawResults().get(2).rawResult()); // 后续样本不受影响
        assertTrue(report.missingRawResults().contains("baseline-v1/s1#0"));
    }

    private static String sha256(Map<String, Object> value) {
        try {
            byte[] bytes = new ObjectMapper().writeValueAsBytes(value);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
