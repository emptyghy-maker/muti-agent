package com.ghy.mutiagent.rule;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultsResolverTest {

    @Test
    void 预算取区间中位数乘天数人数() {
        // 南京人均日消费 400~800：中位数 600 × 3天 × 2人 = 3600
        BigDecimal b = DefaultsResolver.defaultBudget(new BigDecimal("400"), new BigDecimal("800"), 3, 2);
        assertThat(b).isEqualByComparingTo("3600");
    }

    @Test
    void 预算区间缺失时兜底3000() {
        assertThat(DefaultsResolver.defaultBudget(null, null, 3, 2)).isEqualByComparingTo("3000");
        assertThat(DefaultsResolver.defaultBudget(BigDecimal.ZERO, new BigDecimal("800"), 3, 2))
                .isEqualByComparingTo("3000");
    }

    @Test
    void 天数和人数默认值() {
        assertThat(DefaultsResolver.defaultDays()).isEqualTo(3);
        assertThat(DefaultsResolver.defaultPeople()).isEqualTo(2);
    }

    @Test
    void 软偏好默认值() {
        assertThat(DefaultsResolver.defaultAttractionType()).isEqualTo("混合");
        assertThat(DefaultsResolver.defaultFoodTaste()).isEqualTo("本地特色菜");
        assertThat(DefaultsResolver.defaultEnergyLevel()).isEqualTo("一般");
        assertThat(DefaultsResolver.defaultHotelStyle()).isEqualTo("性价比优先");
    }
}
