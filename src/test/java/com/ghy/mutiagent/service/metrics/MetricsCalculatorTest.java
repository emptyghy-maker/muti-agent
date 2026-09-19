package com.ghy.mutiagent.service.metrics;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 指标与费用口径：分母去重、成本下界、约束/换批分母、nearest-rank P95 */
class MetricsCalculatorTest {

    @Test
    void operationDenominatorDedupsReplay() {
        List<MetricsCalculator.LogicalOperationEvent> events = List.of(
                new MetricsCalculator.LogicalOperationEvent("a", "COMMITTED", false),
                new MetricsCalculator.LogicalOperationEvent("b", "COMMITTED", false),
                new MetricsCalculator.LogicalOperationEvent("c", "COMMITTED", false),
                new MetricsCalculator.LogicalOperationEvent("d", "FAILED", false),
                new MetricsCalculator.LogicalOperationEvent("e", "CANCELLED", false),
                new MetricsCalculator.LogicalOperationEvent("a", "COMMITTED", true));
        MetricsCalculator.OperationMetrics m = MetricsCalculator.operations(events);
        assertEquals(5, m.operationCount());
        assertEquals(3, m.successCount());
        assertEquals(0.6, m.successRate());
        assertEquals(1, m.replayedCount());
    }

    @Test
    void operationDuplicateWithoutFlagStillDedups() {
        List<MetricsCalculator.LogicalOperationEvent> events = List.of(
                new MetricsCalculator.LogicalOperationEvent("a", "COMMITTED", false),
                new MetricsCalculator.LogicalOperationEvent("a", "FAILED", false));
        MetricsCalculator.OperationMetrics m = MetricsCalculator.operations(events);
        assertEquals(1, m.operationCount());
        assertEquals(1, m.successCount());
    }

    @Test
    void operationEmptyDenominatorHasNoRate() {
        MetricsCalculator.OperationMetrics m = MetricsCalculator.operations(List.of());
        assertEquals(0, m.operationCount());
        assertNull(m.successRate());
    }

    @Test
    void costIncludesFailedRetryAttempts() {
        List<MetricsCalculator.CostAttemptEvent> attempts = List.of(
                new MetricsCalculator.CostAttemptEvent("a", new BigDecimal("10.00"), true),
                new MetricsCalculator.CostAttemptEvent("b", new BigDecimal("20.00"), false),
                new MetricsCalculator.CostAttemptEvent("b", new BigDecimal("30.00"), true));
        MetricsCalculator.CostMetrics m = MetricsCalculator.costs(attempts);
        assertEquals(new BigDecimal("60.00"), m.totalKnownCost());
        assertEquals(2, m.successfulPlanCount());
        assertEquals(new BigDecimal("30.00"), m.costPerSuccessfulPlan());
        assertEquals(0, m.unknownCostCount());
        assertTrue(m.costComplete());
        Map<String, Object> map = m.toMap();
        assertEquals("60.00", map.get("totalCost"));
        assertEquals("30.00", map.get("costPerSuccessfulPlan"));
    }

    @Test
    void zeroSuccessAndUnknownCostNeverReportZeroUnitCost() {
        List<MetricsCalculator.CostAttemptEvent> attempts = List.of(
                new MetricsCalculator.CostAttemptEvent("a", null, false));
        MetricsCalculator.CostMetrics m = MetricsCalculator.costs(attempts);
        assertNull(m.costPerSuccessfulPlan());
        assertEquals(1, m.unknownCostCount());
        assertFalse(m.costComplete());
        assertNull(m.toMap().get("costPerSuccessfulPlan"));
    }

    @Test
    void zeroSuccessWithKnownCostAlsoHasNullUnitCost() {
        List<MetricsCalculator.CostAttemptEvent> attempts = List.of(
                new MetricsCalculator.CostAttemptEvent("a", new BigDecimal("50.00"), false));
        MetricsCalculator.CostMetrics m = MetricsCalculator.costs(attempts);
        assertNull(m.costPerSuccessfulPlan());
        assertEquals(0, m.successfulPlanCount());
        assertEquals(0, m.unknownCostCount());
        assertTrue(m.costComplete());
    }

    @Test
    void constraintAndPatchMetricsUseExplicitDenominators() {
        List<MetricsCalculator.EvaluatedPlanEvent> plans = List.of(
                new MetricsCalculator.EvaluatedPlanEvent("p1", 0),
                new MetricsCalculator.EvaluatedPlanEvent("p2", 1),
                new MetricsCalculator.EvaluatedPlanEvent("p3", 0));
        List<MetricsCalculator.PatchOutcomeEvent> patches = List.of(
                new MetricsCalculator.PatchOutcomeEvent("m1", 0),
                new MetricsCalculator.PatchOutcomeEvent("m2", 2));
        MetricsCalculator.ConstraintMetrics m = MetricsCalculator.constraints(plans, patches);
        assertEquals(2, m.hardPassNumerator());
        assertEquals(3, m.hardPassDenominator());
        assertEquals(0.5, m.patchViolationRate());
        assertEquals(2, m.outsideChangedNodes());
        assertEquals(0, m.missingCriteriaCount());
    }

    @Test
    void plansWithoutCriteriaExcludedFromDenominatorAndReportedMissing() {
        List<MetricsCalculator.EvaluatedPlanEvent> plans = List.of(
                new MetricsCalculator.EvaluatedPlanEvent("p1", 0),
                new MetricsCalculator.EvaluatedPlanEvent("p2", null));
        MetricsCalculator.ConstraintMetrics m = MetricsCalculator.constraints(plans, List.of());
        assertEquals(1, m.hardPassNumerator());
        assertEquals(1, m.hardPassDenominator());
        assertEquals(1, m.missingCriteriaCount());
        assertNull(m.patchViolationRate());
    }

    @Test
    void p95NearestRankMatchesManualExample() {
        List<Long> durations = LongStream.rangeClosed(1, 20).boxed().toList();
        MetricsCalculator.LatencyMetrics m = MetricsCalculator.latency(durations, List.of(70L, 70L), 100L, 20L);
        assertEquals(20, m.sampleCount());
        assertEquals(19L, m.p95Ms());
        assertEquals(140L, m.providerWorkMs());
        assertEquals(100L, m.operationWallMs());
        assertEquals(20L, m.queueMs());
        assertEquals(MetricsCalculator.P95_ALGORITHM, m.toMap().get("p95Algorithm"));
    }

    @Test
    void p95SingleSampleIsThatSample() {
        MetricsCalculator.LatencyMetrics m = MetricsCalculator.latency(List.of(42L), List.of(), 0L, 0L);
        assertEquals(42L, m.p95Ms());
        assertEquals(1, m.sampleCount());
    }

    @Test
    void p95EmptySampleSetIsNull() {
        MetricsCalculator.LatencyMetrics m = MetricsCalculator.latency(List.of(), List.of(), 0L, 0L);
        assertNull(m.p95Ms());
        assertEquals(0, m.sampleCount());
    }
}
