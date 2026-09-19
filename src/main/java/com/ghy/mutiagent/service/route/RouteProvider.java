package com.ghy.mutiagent.service.route;

import com.ghy.mutiagent.model.RouteFact;
import com.ghy.mutiagent.model.RouteQuery;

/**
 * S10 真实路线提供方接口：按查询返回路线事实（REALTIME）。
 * 超时/失败由 RouteFactService 捕获并降级为明确标注的 ESTIMATE 事实，
 * 禁止把过期缓存标为实时导航。
 */
public interface RouteProvider {

    RouteFact fetch(RouteQuery query);
}
