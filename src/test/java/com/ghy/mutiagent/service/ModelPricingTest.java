package com.ghy.mutiagent.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelPricingTest {

    private ModelPricing pricing() {
        ModelPricing p = new ModelPricing();
        ModelPricing.Price flash = new ModelPricing.Price();
        flash.setIn(new BigDecimal("0.225"));
        flash.setOut(new BigDecimal("0.974"));
        ModelPricing.Price max = new ModelPricing.Price();
        max.setIn(new BigDecimal("12"));
        max.setOut(new BigDecimal("36"));
        p.setPrices(Map.of("qwen3.7-flash", flash, "qwen3.8-max", max));
        return p;
    }

    @Test
    void flash模型百万输入百万输出费用为一千一百九十九() {
        BigDecimal cost = pricing().estimate("qwen3.7-flash", 1_000_000, 1_000_000);
        assertThat(cost).isEqualByComparingTo("1.199");
    }

    @Test
    void max模型百万输入百万输出费用为四十八元() {
        BigDecimal cost = pricing().estimate("qwen3.8-max", 1_000_000, 1_000_000);
        assertThat(cost).isEqualByComparingTo("48");
    }

    @Test
    void 小量token费用按比例折算() {
        BigDecimal cost = pricing().estimate("qwen3.7-flash", 1000, 1000);
        assertThat(cost).isEqualByComparingTo("0.001199");
    }

    @Test
    void 未知模型返回空() {
        assertThat(pricing().estimate("kimi-k3", 1000, 1000)).isNull();
    }

    @Test
    void token为零时返回空() {
        assertThat(pricing().estimate("qwen3.7-flash", 0, 0)).isNull();
        assertThat(pricing().estimate(null, 100, 100)).isNull();
    }
}
