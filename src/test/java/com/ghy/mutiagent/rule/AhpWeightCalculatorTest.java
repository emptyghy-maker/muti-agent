package com.ghy.mutiagent.rule;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AhpWeightCalculatorTest {

    @Test
    void 默认权重满足路径优大于成本低大于旅游需求大于美食需求() {
        Map<String, Double> w = AhpWeightCalculator.defaultWeights();
        assertThat(w.get("path")).isGreaterThan(w.get("cost"));
        assertThat(w.get("cost")).isGreaterThan(w.get("sightseeing"));
        assertThat(w.get("sightseeing")).isGreaterThan(w.get("food"));
        double total = w.values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(total).isBetween(0.999, 1.001);
    }

    @Test
    void 美食需求明显突出时美食权重显著上升() {
        Map<String, Integer> needs = Map.of("path", 2, "cost", 3, "sightseeing", 2, "food", 5);
        assertThat(AhpWeightCalculator.dominantNeed(needs, "food")).isTrue();
        Map<String, Double> w = AhpWeightCalculator.adjust(needs);
        double defaultFood = AhpWeightCalculator.defaultWeights().get("food");
        assertThat(w.get("food")).isGreaterThan(defaultFood * 1.5);
        assertThat(w.get("food")).isGreaterThan(w.get("sightseeing"));
    }

    @Test
    void 强度全相等时权重与默认一致() {
        Map<String, Double> w = AhpWeightCalculator.adjust(Map.of("path", 3, "cost", 3, "sightseeing", 3, "food", 3));
        Map<String, Double> d = AhpWeightCalculator.defaultWeights();
        for (String k : d.keySet()) {
            assertThat(w.get(k)).isCloseTo(d.get(k), org.assertj.core.data.Offset.offset(0.002));
        }
    }
}
