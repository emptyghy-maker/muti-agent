package com.ghy.mutiagent.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * S10 路线事实：排程、详情、计费消费同一份事实快照的唯一载体。
 * kind=REALTIME 为实时导航事实；kind=ESTIMATE 为规则估算（confidence=LOW，
 * source=simple-route-estimator，并附 ROUTE_PROVIDER_UNAVAILABLE 原因）。
 * 过期事实（validUntil 已过）不得再标为实时。
 */
public record RouteFact(String factId, String mode, int durationMin, BigDecimal cost,
                        double distanceKm, String kind, String source,
                        Instant fetchedAt, Instant validUntil, String confidence,
                        String providerVersion, List<String> reasonCodes) {

    public static final String KIND_REALTIME = "REALTIME";
    public static final String KIND_ESTIMATE = "ESTIMATE";
    public static final String CONFIDENCE_LOW = "LOW";
    public static final String CONFIDENCE_HIGH = "HIGH";
    public static final String SOURCE_SIMPLE_ESTIMATOR = "simple-route-estimator";
    public static final String REASON_ROUTE_PROVIDER_UNAVAILABLE = "ROUTE_PROVIDER_UNAVAILABLE";
}
