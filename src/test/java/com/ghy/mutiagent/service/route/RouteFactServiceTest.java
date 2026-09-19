package com.ghy.mutiagent.service.route;

import com.ghy.mutiagent.model.PlaceKey;
import com.ghy.mutiagent.model.PlaceType;
import com.ghy.mutiagent.model.RouteFact;
import com.ghy.mutiagent.model.RouteQuery;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S10 路线事实服务契约：估算单一口径、缓存维度隔离、过期不标实时、估算降级标识。
 */
class RouteFactServiceTest {

    private static RouteQuery q(String from, String to, String date, String mode) {
        return new RouteQuery(key(from), key(to), date, mode, null, null, 0, 0);
    }

    private static PlaceKey key(String s) {
        int sep = s.indexOf(':');
        return PlaceKey.of(PlaceType.valueOf(s.substring(0, sep)), Long.parseLong(s.substring(sep + 1)));
    }

    private static RouteFact realtime(String id, int minutes, String cost) {
        return new RouteFact(id, "TRANSIT", minutes, new BigDecimal(cost), 3.0,
                RouteFact.KIND_REALTIME, "scripted-provider", Instant.parse("2030-05-01T08:00:00Z"),
                Instant.parse("2030-05-01T09:00:00Z"), RouteFact.CONFIDENCE_HIGH, "v1", List.of());
    }

    /** 估算口径与既有确定性规则一致：4/20/30 km/h + 10 分钟缓冲、最短 15 分钟 */
    @Test
    void estimatorMatchesLegacyArithmetic() {
        assertEquals(15, RouteFactEstimator.durationMin("WALK", 0.2));
        assertEquals(25, RouteFactEstimator.durationMin("TRANSIT", 5.0));
        assertEquals(26, RouteFactEstimator.durationMin("TAXI", 8.0));
        assertEquals(30, RouteFactEstimator.durationMin(null, 0));
        assertEquals("WALK", RouteFactEstimator.modeForKm(0.5));
        assertEquals("TRANSIT", RouteFactEstimator.modeForKm(5));
        assertEquals("TAXI", RouteFactEstimator.modeForKm(20));
        assertEquals(BigDecimal.ZERO, RouteFactEstimator.cost("WALK", 0.5, 2));
        assertEquals(new BigDecimal("4"), RouteFactEstimator.cost("TRANSIT", 5, 2));
    }

    /** 缓存命中返回同一事实（同一 factId），不重复调用提供方 */
    @Test
    void cacheHitReusesFact() {
        AtomicInteger calls = new AtomicInteger();
        RouteFactService svc = new RouteFactService(Clock.fixed(
                Instant.parse("2030-05-01T08:00:00Z"), ZoneId.of("UTC")), 3600_000);
        svc.setProvider(q -> {
            calls.incrementAndGet();
            return realtime("f-" + calls.get(), 37, "6");
        });
        RouteFact f1 = svc.fact(q("ATTRACTION:1", "RESTAURANT:2", "2030-05-01", "WALK"));
        RouteFact f2 = svc.fact(q("ATTRACTION:1", "RESTAURANT:2", "2030-05-01", "WALK"));
        assertEquals(f1.factId(), f2.factId());
        assertEquals(1, calls.get());
    }

    /** 缓存维度隔离：类型/日期/方式任一不同都不得错误命中 */
    @Test
    void cacheKeyDimensionsIsolated() {
        AtomicInteger calls = new AtomicInteger();
        RouteFactService svc = new RouteFactService(Clock.fixed(
                Instant.parse("2030-05-01T08:00:00Z"), ZoneId.of("UTC")), 3600_000);
        svc.setProvider(q -> realtime("f-" + calls.incrementAndGet(), 37, "6"));
        svc.fact(q("ATTRACTION:1", "RESTAURANT:2", "2030-05-01", "WALK"));
        svc.fact(q("HOTEL:1", "RESTAURANT:2", "2030-05-01", "WALK"));
        svc.fact(q("ATTRACTION:1", "RESTAURANT:2", "2030-05-02", "WALK"));
        svc.fact(q("ATTRACTION:1", "RESTAURANT:2", "2030-05-01", "TRANSIT"));
        assertEquals(4, calls.get());
    }

    /** 过期缓存不沿用：刷新失败时给出明确 ESTIMATE 标识与原因码 */
    @Test
    void expiredCacheRefreshesAndTimesOutToEstimate() {
        MutableClock clock = new MutableClock(Instant.parse("2030-05-01T08:00:00Z"));
        RouteFactService svc = new RouteFactService(clock, 3600_000);
        AtomicInteger phase = new AtomicInteger(0);
        svc.setProvider(q -> {
            if (phase.get() == 0) {
                // 短有效期真实事实（1 秒后过期）
                return new RouteFact("r1", "TRANSIT", 37, new BigDecimal("6"), 3.0,
                        RouteFact.KIND_REALTIME, "scripted-provider", clock.instant(),
                        clock.instant().plusSeconds(1), RouteFact.CONFIDENCE_HIGH, "v1", List.of());
            }
            throw new IllegalStateException("TIMEOUT");
        });
        RouteFact first = svc.fact(q("ATTRACTION:1", "RESTAURANT:2", "2030-05-01", "TRANSIT"));
        assertEquals(RouteFact.KIND_REALTIME, first.kind());
        clock.advanceSeconds(2);
        phase.set(1);
        RouteFact second = svc.fact(q("ATTRACTION:1", "RESTAURANT:2", "2030-05-01", "TRANSIT"));
        assertEquals(RouteFact.KIND_ESTIMATE, second.kind());
        assertEquals(RouteFact.SOURCE_SIMPLE_ESTIMATOR, second.source());
        assertEquals(RouteFact.CONFIDENCE_LOW, second.confidence());
        assertTrue(second.reasonCodes().contains(RouteFact.REASON_ROUTE_PROVIDER_UNAVAILABLE));
        assertTrue(second.fetchedAt() != null && second.validUntil() != null);
    }

    /** 提供方缺失：全部走估算降级（不抛异常） */
    @Test
    void withoutProviderFallsBackToEstimate() {
        RouteFactService svc = new RouteFactService(Clock.fixed(
                Instant.parse("2030-05-01T08:00:00Z"), ZoneId.of("UTC")), 3600_000);
        RouteFact f = svc.fact(q("ATTRACTION:1", "RESTAURANT:2", "2030-05-01", "TRANSIT"));
        assertEquals(RouteFact.KIND_ESTIMATE, f.kind());
    }

    /** 可控墙钟（桥接同款）：供超时/过期用例推进时间 */
    static final class MutableClock extends Clock {
        private volatile Instant now;
        private final ZoneId zone = ZoneId.of("UTC");

        MutableClock(Instant start) {
            this.now = start;
        }

        void advanceSeconds(long s) {
            now = now.plusSeconds(s);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId z) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
