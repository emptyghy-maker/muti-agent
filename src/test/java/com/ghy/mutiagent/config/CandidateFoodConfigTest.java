package com.ghy.mutiagent.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateFoodConfigTest {

    private CandidateFoodConfig cfg() {
        return new CandidateFoodConfig();
    }

    @Test
    void 两天目标十八家() {
        // 3+6=9 家/天 × 2 天 = 18，大于最低 15
        assertThat(cfg().resolveTarget(2, 20)).isEqualTo(18);
    }

    @Test
    void 一天按最低十五家保底() {
        // 9×1=9 < 15 → 取 15
        assertThat(cfg().resolveTarget(1, 20)).isEqualTo(15);
    }

    @Test
    void 天数多时受候选池大小限制() {
        // 9×5=45，但池里只有 14 家
        assertThat(cfg().resolveTarget(5, 14)).isEqualTo(14);
    }

    @Test
    void 自定义每餐数量生效() {
        CandidateFoodConfig c = cfg();
        c.setBreakfastPerDay(4);
        c.setMainMealPerDay(8);
        c.setMinTotal(10);
        assertThat(c.resolveTarget(2, 100)).isEqualTo(24);
    }
}
