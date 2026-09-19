package com.ghy.mutiagent.service.route;

import com.ghy.mutiagent.common.GeoUtils;
import com.ghy.mutiagent.model.RouteFact;
import com.ghy.mutiagent.model.RouteQuery;
import com.ghy.mutiagent.rule.BudgetCalculator;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * S10 路线估算器（单一事实源）：排程、详情、计费的估算口径统一在这里，
 * 不再出现「一处 20km/h、一处其他速度」。
 * 口径与既有确定性规则一致：≤1km 步行 4km/h、1~8km 公交 20km/h、>8km 30km/h，
 * 固定 10 分钟缓冲、最短 15 分钟；公交 2 元×人数、出租车按计价公式。
 * SimpleRoutePlanner 的多方案展示仍可存在，但权威时长/费用取 RouteFact。
 */
public final class RouteFactEstimator {

    private RouteFactEstimator() {
    }

    public static String modeForKm(double km) {
        return km <= 1 ? "WALK" : km <= 8 ? "TRANSIT" : "TAXI";
    }

    public static int durationMin(String mode, double km) {
        if (mode == null) {
            return STATION_MIN;
        }
        return switch (mode) {
            case "WALK" -> Math.max(MIN_MIN, (int) Math.round(km / 4.0 * 60) + BUFFER_MIN);
            case "TRANSIT" -> Math.max(MIN_MIN, (int) Math.round(km / 20.0 * 60) + BUFFER_MIN);
            case "TAXI" -> Math.max(MIN_MIN, (int) Math.round(km / 30.0 * 60) + BUFFER_MIN);
            default -> STATION_MIN;
        };
    }

    public static BigDecimal cost(String mode, double km, int party) {
        return switch (mode == null ? "" : mode) {
            case "WALK" -> BigDecimal.ZERO;
            case "TRANSIT" -> BigDecimal.valueOf(2L * Math.max(1, party));
            case "TAXI" -> BudgetCalculator.taxiCost(km);
            default -> BigDecimal.ZERO;
        };
    }

    /** 降级估算事实：明确 ESTIMATE / simple-route-estimator / LOW，并附原因码 */
    public static RouteFact estimate(RouteQuery query, Clock clock, long ttlMillis) {
        double km = query.fromCoord() == null || query.toCoord() == null
                ? 0 : GeoUtils.distanceKm(query.fromCoord()[0], query.fromCoord()[1],
                        query.toCoord()[0], query.toCoord()[1]);
        String mode = query.mode() == null || query.mode().isBlank() ? modeForKm(km) : query.mode();
        Instant fetchedAt = clock.instant();
        return new RouteFact("est-" + UUID.randomUUID(),
                mode, durationMin(mode, km), cost(mode, km, 2),
                Math.round(km * 10) / 10.0,
                RouteFact.KIND_ESTIMATE, RouteFact.SOURCE_SIMPLE_ESTIMATOR,
                fetchedAt, fetchedAt.plusMillis(ttlMillis), RouteFact.CONFIDENCE_LOW,
                "none", List.of(RouteFact.REASON_ROUTE_PROVIDER_UNAVAILABLE));
    }

    private static final int MIN_MIN = 15;
    private static final int BUFFER_MIN = 10;
    private static final int STATION_MIN = 30;
}
