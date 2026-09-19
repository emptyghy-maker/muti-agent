package com.ghy.mutiagent.service.eval;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 决策引擎：质量退化/成本越界/证据不足/充分证据四类分支 + 永不自动发布 */
class ExperimentDecisionTest {

    private static ExperimentDecision.ArmSummary arm(Double score, Integer hard, String cost) {
        return new ExperimentDecision.ArmSummary(score, hard,
                cost == null ? null : new BigDecimal(cost));
    }

    @Test
    void hardConstraintRegressionRejects() {
        ExperimentDecision.DecisionInput in = new ExperimentDecision.DecisionInput(
                null, null, null, null,
                arm(null, 0, "100.00"), arm(null, 1, "50.00"),
                null, null, null, "STUB", false);
        ExperimentDecision.DecisionResult r = ExperimentDecision.decide(in);
        assertEquals(ExperimentDecision.REJECT, r.decision());
        assertTrue(r.reasonCodes().contains(ExperimentDecision.HARD_CONSTRAINT_REGRESSION));
        assertFalse(r.published());
    }

    @Test
    void costOverToleranceRejectsEvenWithBetterQuality() {
        ExperimentDecision.DecisionInput in = new ExperimentDecision.DecisionInput(
                null, null, null, null,
                arm(0.8, null, "10.00"), arm(0.9, null, "13.00"),
                null, new BigDecimal("1.2"), null, "STUB", false);
        ExperimentDecision.DecisionResult r = ExperimentDecision.decide(in);
        assertEquals(ExperimentDecision.REJECT, r.decision());
        assertTrue(r.reasonCodes().contains(ExperimentDecision.COST_LIMIT_EXCEEDED));
        assertFalse(r.reasonCodes().contains(ExperimentDecision.HARD_CONSTRAINT_REGRESSION));
    }

    @Test
    void insufficientEvidenceIsInconclusive() {
        ExperimentDecision.DecisionInput in = new ExperimentDecision.DecisionInput(
                5, 30, 1, 1,
                arm(null, 0, null), arm(null, 0, null),
                null, null, null, "STUB", false);
        ExperimentDecision.DecisionResult r = ExperimentDecision.decide(in);
        assertEquals(ExperimentDecision.INCONCLUSIVE, r.decision());
        assertTrue(r.reasonCodes().contains(ExperimentDecision.INSUFFICIENT_EVIDENCE));
        assertTrue(r.reasonCodes().contains(ExperimentDecision.UNKNOWN_COST));
        assertFalse(r.published());
    }

    @Test
    void fullEvidenceOnlyEligibleForReviewNeverAutoPublishes() {
        ExperimentDecision.DecisionInput in = new ExperimentDecision.DecisionInput(
                30, 30, 0, 0,
                arm(0.8, 0, "10.00"), arm(0.85, 0, "11.00"),
                0.01, new BigDecimal("1.2"), List.of(0.02, 0.08), "LIVE_FIXTURE", false);
        ExperimentDecision.DecisionResult r = ExperimentDecision.decide(in);
        assertEquals(ExperimentDecision.ELIGIBLE_FOR_REVIEW, r.decision());
        assertTrue(r.reasonCodes().isEmpty());
        assertFalse(r.published());
    }

    @Test
    void improvementBelowMinimumRejects() {
        ExperimentDecision.DecisionInput in = new ExperimentDecision.DecisionInput(
                30, 30, 0, 0,
                arm(0.8, 0, "10.00"), arm(0.80, 0, "9.00"),
                0.01, new BigDecimal("1.2"), null, "STUB", false);
        ExperimentDecision.DecisionResult r = ExperimentDecision.decide(in);
        assertEquals(ExperimentDecision.REJECT, r.decision());
        assertTrue(r.reasonCodes().contains(ExperimentDecision.INSUFFICIENT_IMPROVEMENT));
    }

    @Test
    void missingRunsAloneIsInconclusive() {
        ExperimentDecision.DecisionInput in = new ExperimentDecision.DecisionInput(
                30, 30, 2, 0,
                arm(0.8, 0, "10.00"), arm(0.9, 0, "10.00"),
                0.01, new BigDecimal("1.2"), null, "STUB", false);
        ExperimentDecision.DecisionResult r = ExperimentDecision.decide(in);
        assertEquals(ExperimentDecision.INCONCLUSIVE, r.decision());
        assertTrue(r.reasonCodes().contains(ExperimentDecision.INSUFFICIENT_EVIDENCE));
    }

    @Test
    void armSummaryFromMapHandlesMissingFields() {
        ExperimentDecision.ArmSummary a = ExperimentDecision.ArmSummary.fromMap(
                java.util.Map.of("hardViolations", 0, "costPerSuccess", "10.00"));
        assertEquals(null, a.score());
        assertEquals(0, a.hardViolations());
        assertEquals(new BigDecimal("10.00"), a.costPerSuccess());
    }
}
