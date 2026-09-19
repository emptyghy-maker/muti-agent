package com.ghy.mutiagent.service.route;

import com.ghy.mutiagent.model.RouteOption;

import java.util.List;

/**
 * 两点间路径规划接口。
 * 默认实现 SimpleRoutePlanner（坐标+距离规则估算）；预留 AmapRoutePlanner（高德真实导航）。
 */
public interface RoutePlanner {

    List<RouteOption> plan(double fromLng, double fromLat, double toLng, double toLat);
}
