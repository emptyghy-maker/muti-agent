package com.ghy.mutiagent.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * 模型计费单价（元 / 百万 token），绑定 application-dev.yml 的 llm.pricing。
 * 未知模型返回 null（前端显示"-"），记录失败只影响展示、不影响业务。
 */
@Component
@ConfigurationProperties(prefix = "llm.pricing")
public class ModelPricing {

    private Map<String, Price> prices = new HashMap<>();

    public Map<String, Price> getPrices() {
        return prices;
    }

    public void setPrices(Map<String, Price> prices) {
        this.prices = prices == null ? new HashMap<>() : prices;
    }

    /** 估算费用（元，6 位小数）；模型未知或 token 为 0 时返回 null */
    public BigDecimal estimate(String model, long inputTokens, long outputTokens) {
        if (model == null || model.isBlank() || (inputTokens <= 0 && outputTokens <= 0)) {
            return null;
        }
        Price p = prices.get(model);
        if (p == null || p.getIn() == null || p.getOut() == null) {
            return null;
        }
        BigDecimal cost = p.getIn().multiply(BigDecimal.valueOf(inputTokens))
                .add(p.getOut().multiply(BigDecimal.valueOf(outputTokens)));
        return cost.divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
    }

    /** 单个模型的输入/输出单价 */
    public static class Price {
        private BigDecimal in;
        private BigDecimal out;

        public BigDecimal getIn() {
            return in;
        }

        public void setIn(BigDecimal in) {
            this.in = in;
        }

        public BigDecimal getOut() {
            return out;
        }

        public void setOut(BigDecimal out) {
            this.out = out;
        }
    }
}
