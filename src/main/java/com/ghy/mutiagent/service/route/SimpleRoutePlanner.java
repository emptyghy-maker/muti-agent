package com.ghy.mutiagent.service.route;

import com.ghy.mutiagent.common.GeoUtils;
import com.ghy.mutiagent.model.RouteOption;
import com.ghy.mutiagent.rule.BudgetCalculator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 简化路径规划：基于坐标直线距离的规则估算（学习阶段零外部依赖）。
 *
 * - 步行：5km/h；
 * - 公交/地铁：20km/h + 10 分钟候车换乘；
 * - 打车：35km/h，费用见 BudgetCalculator.transportCost。
 * 后续可切换 AmapRoutePlanner（高德 API）获得真实方案，业务代码不变。
 */
@Component
public class SimpleRoutePlanner implements RoutePlanner {

    @Override
    public List<RouteOption> plan(double fromLng, double fromLat, double toLng, double toLat) {
        double km = GeoUtils.distanceKm(fromLng, fromLat, toLng, toLat);
        return List.of(walk(km), transit(km), taxi(km));
    }

    private RouteOption walk(double km) {
        RouteOption o = new RouteOption();
        o.setMode("步行");
        o.setDurationMin((int) Math.round(km / 5.0 * 60));
        o.setCost(BigDecimal.ZERO);
        o.setSteps(List.of("沿步行导航约 " + round(km) + " 公里", "预计 " + o.getDurationMin() + " 分钟"));
        return o;
    }

    private RouteOption transit(double km) {
        RouteOption o = new RouteOption();
        o.setMode("公交/地铁");
        o.setDurationMin((int) Math.round(km / 20.0 * 60 + 10));
        o.setCost(BigDecimal.valueOf(2));
        o.setSteps(List.of("步行至最近的公交/地铁站", "乘车约 " + round(km) + " 公里（含换乘等候）",
                "预计 " + o.getDurationMin() + " 分钟"));
        return o;
    }

    private RouteOption taxi(double km) {
        RouteOption o = new RouteOption();
        o.setMode("打车");
        o.setDurationMin((int) Math.round(km / 35.0 * 60));
        o.setCost(BudgetCalculator.taxiCost(km));
        o.setSteps(List.of("网约车/出租车直达，约 " + round(km) + " 公里", "预计 " + o.getDurationMin() + " 分钟"));
        return o;
    }

    private double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
