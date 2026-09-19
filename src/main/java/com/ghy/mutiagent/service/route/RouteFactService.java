package com.ghy.mutiagent.service.route;

import com.ghy.mutiagent.model.RouteFact;
import com.ghy.mutiagent.model.RouteQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S10 路线事实服务：统一缓存 + 提供方调用 + 估算降级。
 *
 * - 缓存键包含地点类型与 ID、日期、方式、坐标版本与提供方版本（维度隔离）；
 * - 命中后仍验证有效期（validUntil，含 TTL 上限），过期成功缓存不得标为实时；
 * - 提供方超时/失败 → 明确 ESTIMATE（simple-route-estimator / LOW / ROUTE_PROVIDER_UNAVAILABLE），
 *   不把旧值标为实时导航。
 */
public class RouteFactService {

    private static final Logger log = LoggerFactory.getLogger(RouteFactService.class);

    private final Clock clock;
    private final long ttlMillis;
    private volatile RouteProvider provider;
    private final Map<String, RouteFact> cache = new ConcurrentHashMap<>();

    public RouteFactService(Clock clock, long ttlMillis) {
        this.clock = clock;
        this.ttlMillis = ttlMillis;
    }

    /** 真实提供方可选装配：未装配时全部走估算降级（学习阶段零外部依赖） */
    public void setProvider(RouteProvider provider) {
        this.provider = provider;
    }

    /** Observability 薄埋点（可空：手动装配的测试进程为 null；模块关闭时内部 noop） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ghy.mutiagent.observability.collection.ObsInstrumentation obsInstrumentation;

    public RouteFact fact(RouteQuery query) {
        String key = cacheKey(query);
        RouteFact hit = cache.get(key);
        Instant now = clock.instant();
        if (hit != null && now.isBefore(hit.validUntil())) {
            obsTool(null, query, "ROUTE", "CACHE_HIT", false,
                    java.util.Map.of("factId", hit.factId() == null ? "" : hit.factId(),
                            "source", hit.source() == null ? "" : hit.source()));
            return hit;
        }
        if (hit != null) {
            log.warn("[RouteFact] 缓存事实已过期（validUntil={}），重新获取不沿用旧值", hit.validUntil());
        }
        RouteFact fresh = fetchFresh(query);
        cache.put(key, fresh);
        return fresh;
    }

    private RouteFact fetchFresh(RouteQuery query) {
        RouteProvider p = provider;
        if (p != null) {
            try {
                RouteFact f = p.fetch(query);
                RouteFact capped = capValidity(f, clock.instant());
                obsTool(null, query, "ROUTE", "PROVIDER_OK", true,
                        java.util.Map.of("factId", capped.factId() == null ? "" : capped.factId(),
                                "source", capped.source() == null ? "" : capped.source(),
                                "providerVersion", capped.providerVersion() == null ? ""
                                        : capped.providerVersion()));
                return capped;
            } catch (Exception e) {
                log.warn("[RouteFact] 路线提供方不可用（{}），降级为估算", e.getMessage());
                obsTool(null, query, "ROUTE", "PROVIDER_ERROR_FALLBACK", true,
                        java.util.Map.of("reason", e.getClass().getSimpleName()));
            }
        }
        return RouteFactEstimator.estimate(query, clock, ttlMillis);
    }

    private void obsTool(String operationId, RouteQuery query, String tool, String outcome,
                         boolean external, java.util.Map<String, Object> summary) {
        if (obsInstrumentation != null) {
            obsInstrumentation.toolEvent(operationId, null, null, tool, outcome, external, summary);
        }
    }

    /** 事实有效期受 TTL 上限约束：提供方给的值只可短不可超 */
    private RouteFact capValidity(RouteFact f, Instant fetchedAt) {
        Instant max = fetchedAt.plusMillis(ttlMillis);
        if (f.validUntil() == null || f.validUntil().isAfter(max)) {
            return new RouteFact(f.factId(), f.mode(), f.durationMin(), f.cost(), f.distanceKm(),
                    f.kind(), f.source(), f.fetchedAt() == null ? fetchedAt : f.fetchedAt(), max,
                    f.confidence(), f.providerVersion(), f.reasonCodes());
        }
        return f;
    }

    private String cacheKey(RouteQuery q) {
        String coordVersion = "v" + q.coordVersion();
        String coordHash = q.fromCoord() == null || q.toCoord() == null
                ? "nc" : "c" + java.util.Arrays.hashCode(q.fromCoord()) + "-"
                + java.util.Arrays.hashCode(q.toCoord());
        return q.from() + "|" + q.to() + "|" + q.date() + "|" + q.mode() + "|"
                + coordVersion + "|" + coordHash;
    }
}
