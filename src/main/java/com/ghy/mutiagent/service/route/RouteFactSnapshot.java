package com.ghy.mutiagent.service.route;

import com.ghy.mutiagent.common.GeoUtils;
import com.ghy.mutiagent.model.PlaceKey;
import com.ghy.mutiagent.model.RouteFact;
import com.ghy.mutiagent.model.RouteQuery;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * S10 一次计算（一次行程生成/一次详情渲染）内的路线事实快照：
 * 排程、详情、计费消费同一份快照——同一段行程同一日期同一方式只取一次事实，
 * 三个消费方看到的 durationMin/cost/factId 完全一致。
 */
public class RouteFactSnapshot {

    private final RouteFactService factService;
    private final String date;
    private final int constraintRevision;
    private final Map<String, RouteFact> memo = new LinkedHashMap<>();

    public RouteFactSnapshot(RouteFactService factService, String date, int constraintRevision) {
        this.factService = factService;
        this.date = date;
        this.constraintRevision = constraintRevision;
    }

    public String date() {
        return date;
    }

    public int constraintRevision() {
        return constraintRevision;
    }

    /** 相邻节点段的事实：方式由距离统一推定（≤1km 步行 / 1~8km 公交 / >8km 打车） */
    public RouteFact factFor(PlaceKey from, PlaceKey to, double[] fromCoord, double[] toCoord) {
        double km = fromCoord == null || toCoord == null
                ? 0 : GeoUtils.distanceKm(fromCoord[0], fromCoord[1], toCoord[0], toCoord[1]);
        return factFor(from, to, km);
    }

    public RouteFact factFor(PlaceKey from, PlaceKey to, double km) {
        String mode = RouteFactEstimator.modeForKm(km);
        String key = from + "|" + to + "|" + mode;
        RouteFact cached = memo.get(key);
        if (cached != null) {
            return cached;
        }
        RouteFact fact = factService.fact(new RouteQuery(from, to, date, mode,
                null, null, 0, constraintRevision));
        memo.put(key, fact);
        return fact;
    }
}
