package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.PlaceKey;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 价格快照（S05）：地点 → 单价，由已选实体构造。
 * 缺价格返回 null（不得默认免费）；负数视为未核实（由调用方判为未知）。
 */
public record PriceSnapshot(Map<PlaceKey, BigDecimal> prices) {

    public BigDecimal priceOf(PlaceKey key) {
        return prices.get(key);
    }
}
