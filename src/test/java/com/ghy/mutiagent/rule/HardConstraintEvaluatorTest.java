package com.ghy.mutiagent.rule;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S07 统一硬约束出口：三态语义（ELIGIBLE / INELIGIBLE / UNKNOWN）。
 * 硬条件事实未知不算通过；模型标签不能替代官方事实。
 */
class HardConstraintEvaluatorTest {

    private static final HardConstraintEvaluator.HardPolicy POLICY = HardConstraintEvaluator.HardPolicy.of(
            "杭州", Set.of("高强度"), new BigDecimal("200"), false);

    @Test
    void eligibleWhenAllFactsSatisfyPolicy() {
        HardConstraintEvaluator.Verdict v = HardConstraintEvaluator.evaluate(
                HardConstraintEvaluator.HardFact.of("杭州", List.of(), new BigDecimal("100"), null), POLICY);
        assertThat(v.status()).isEqualTo(HardConstraintEvaluator.Status.ELIGIBLE);
    }

    @Test
    void ineligibleOnCityMismatch() {
        HardConstraintEvaluator.Verdict v = HardConstraintEvaluator.evaluate(
                HardConstraintEvaluator.HardFact.of("上海", List.of(), new BigDecimal("100"), null), POLICY);
        assertThat(v.status()).isEqualTo(HardConstraintEvaluator.Status.INELIGIBLE);
        assertThat(v.reasonCode()).isEqualTo(HardConstraintEvaluator.CITY_MISMATCH);
    }

    @Test
    void ineligibleOnForbiddenTag() {
        HardConstraintEvaluator.Verdict v = HardConstraintEvaluator.evaluate(
                HardConstraintEvaluator.HardFact.of("杭州", List.of("高强度"), new BigDecimal("100"), null), POLICY);
        assertThat(v.status()).isEqualTo(HardConstraintEvaluator.Status.INELIGIBLE);
        assertThat(v.reasonCode()).isEqualTo(HardConstraintEvaluator.FORBIDDEN_TAG);
    }

    @Test
    void ineligibleOnOverBudget() {
        HardConstraintEvaluator.Verdict v = HardConstraintEvaluator.evaluate(
                HardConstraintEvaluator.HardFact.of("杭州", List.of(), new BigDecimal("300"), null), POLICY);
        assertThat(v.status()).isEqualTo(HardConstraintEvaluator.Status.INELIGIBLE);
        assertThat(v.reasonCode()).isEqualTo(HardConstraintEvaluator.OVER_BUDGET);
    }

    @Test
    void unknownOnMissingFactsIsNotEligible() {
        HardConstraintEvaluator.Verdict cityMissing = HardConstraintEvaluator.evaluate(
                HardConstraintEvaluator.HardFact.of(null, List.of(), new BigDecimal("100"), null), POLICY);
        assertThat(cityMissing.status()).isEqualTo(HardConstraintEvaluator.Status.UNKNOWN);

        HardConstraintEvaluator.Verdict priceMissing = HardConstraintEvaluator.evaluate(
                HardConstraintEvaluator.HardFact.of("杭州", List.of(), null, null), POLICY);
        assertThat(priceMissing.status()).isEqualTo(HardConstraintEvaluator.Status.UNKNOWN);
        assertThat(priceMissing.reasonCode()).isEqualTo(HardConstraintEvaluator.PRICE_UNKNOWN);
    }

    @Test
    void accessibilityTriState() {
        HardConstraintEvaluator.HardPolicy accessible = HardConstraintEvaluator.HardPolicy.of(
                null, Set.of(), null, true);
        assertThat(HardConstraintEvaluator.evaluate(
                HardConstraintEvaluator.HardFact.of(null, List.of(), null, true), accessible).status())
                .isEqualTo(HardConstraintEvaluator.Status.ELIGIBLE);
        assertThat(HardConstraintEvaluator.evaluate(
                HardConstraintEvaluator.HardFact.of(null, List.of(), null, false), accessible).status())
                .isEqualTo(HardConstraintEvaluator.Status.INELIGIBLE);
        // 事实未知（null）：不算通过，也不伪装成 false
        assertThat(HardConstraintEvaluator.evaluate(
                HardConstraintEvaluator.HardFact.of(null, List.of(), null, null), accessible).status())
                .isEqualTo(HardConstraintEvaluator.Status.UNKNOWN);
    }
}
