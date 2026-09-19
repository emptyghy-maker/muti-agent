package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.common.GeoUtils;
import com.ghy.mutiagent.model.PlaceKey;
import com.ghy.mutiagent.model.PlanNode;
import com.ghy.mutiagent.repository.entity.Attraction;
import com.ghy.mutiagent.repository.entity.Hotel;
import com.ghy.mutiagent.repository.entity.Restaurant;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 单日消费估算（纯函数，仅用于按天展示）：
 * 单日 = Σ门票（按人）+ Σ餐费（人均×人数）+ 住宿（同日同酒店只计一晚）+ 相邻节点交通估算。
 * 交通规则：≤1km 步行 0 元；1~8km 公交 2 元/人；>8km 出租车 起步12 + 2.5元/km（按车辆数）。
 * 往返大交通（机场/高铁）金额未知，此处不计固定价——全程账单（TripBilling）会标记 unknown。
 */
public final class BudgetCalculator {

    private BudgetCalculator() {
    }

    /** 相邻节点单程交通估算：≤1km 步行 0；1~8km 公交按人数；>8km 出租车按车辆数 */
    public static BigDecimal transitCost(double km, int people) {
        if (km <= 1) {
            return BigDecimal.ZERO;
        }
        if (km <= 8) {
            return BigDecimal.valueOf(2).multiply(BigDecimal.valueOf(Math.max(1, people)));
        }
        return taxiCost(km);
    }

    /** 出租车费用（按车辆数）：起步 12 + 2.5元/km（路径推荐与账单共用同一口径） */
    public static BigDecimal taxiCost(double km) {
        if (km <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(12 + 2.5 * km).setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * 计算单日消费。
     * coords：跨类型坐标索引（PlaceKey → [lng,lat]），用于相邻节点交通估算；
     * 节点坐标必须先经 PlaceKeyResolver 解析（transport 节点无实体，不参与相邻估算）。
     */
    public static BigDecimal dayCost(List<PlanNode> nodes, int people,
                                     Map<Long, Attraction> attractions,
                                     Map<Long, Restaurant> restaurants,
                                     Map<Long, Hotel> hotels,
                                     Map<PlaceKey, double[]> coords) {
        BigDecimal total = BigDecimal.ZERO;
        Set<Long> chargedHotels = new HashSet<>();
        for (PlanNode n : nodes) {
            switch (n.getType() == null ? "" : n.getType()) {
                case "attraction", "rest" -> {
                    Attraction a = attractions.get(n.getPlaceId());
                    if (a != null && a.getTicketPrice() != null) {
                        total = total.add(a.getTicketPrice().multiply(BigDecimal.valueOf(people)));
                    }
                }
                case "restaurant" -> {
                    Restaurant r = restaurants.get(n.getPlaceId());
                    if (r != null && r.getAvgPrice() != null) {
                        total = total.add(r.getAvgPrice().multiply(BigDecimal.valueOf(people)));
                    }
                }
                case "hotel" -> {
                    // 住宿去重：同一天同一家酒店只住一晚，酒店节点进出多次不重复计费
                    if (n.getPlaceId() != null && chargedHotels.add(n.getPlaceId())) {
                        Hotel h = hotels.get(n.getPlaceId());
                        if (h != null && h.getPricePerNight() != null) {
                            total = total.add(h.getPricePerNight());
                        }
                    }
                }
                // transport：往返大交通金额未知，不计固定价（全程账单标记 unknown）
                default -> { }
            }
        }
        // 相邻实体节点间交通
        for (int i = 1; i < nodes.size(); i++) {
            Optional<PlaceKey> ka = PlaceKeyResolver.fromNode(nodes.get(i - 1));
            Optional<PlaceKey> kb = PlaceKeyResolver.fromNode(nodes.get(i));
            if (ka.isEmpty() || kb.isEmpty()) {
                continue;
            }
            double[] a = coords == null ? null : coords.get(ka.get());
            double[] b = coords == null ? null : coords.get(kb.get());
            if (a != null && b != null) {
                total = total.add(transitCost(GeoUtils.distanceKm(a[0], a[1], b[0], b[1]), people));
            }
        }
        return total.setScale(0, RoundingMode.HALF_UP);
    }
}
