package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.PlaceKey;
import com.ghy.mutiagent.model.PlaceType;
import com.ghy.mutiagent.model.PlanNode;

import java.util.Optional;

/**
 * 节点 → 复合地点键的唯一转换规则（全项目统一，禁止另起一套 type→表 映射）：
 * attraction/rest → ATTRACTION；restaurant → RESTAURANT；hotel → HOTEL。
 * 交通节点没有数据库实体，返回 empty，不构造 id=0 的假键；非法实体 ID 由验证器拒绝。
 */
public final class PlaceKeyResolver {

    private PlaceKeyResolver() {
    }

    public static Optional<PlaceKey> fromNode(PlanNode n) {
        if (n == null || n.getType() == null || n.getPlaceId() == null || n.getPlaceId() <= 0) {
            return Optional.empty();
        }
        return switch (n.getType()) {
            case "attraction", "rest" -> Optional.of(PlaceKey.of(PlaceType.ATTRACTION, n.getPlaceId()));
            case "restaurant" -> Optional.of(PlaceKey.of(PlaceType.RESTAURANT, n.getPlaceId()));
            case "hotel" -> Optional.of(PlaceKey.of(PlaceType.HOTEL, n.getPlaceId()));
            default -> Optional.empty();
        };
    }
}
