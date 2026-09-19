package com.ghy.mutiagent.config;

import com.ghy.mutiagent.service.TravelSessionService;
import com.ghy.mutiagent.service.route.FactToolService;
import com.ghy.mutiagent.service.route.FactVersionGate;
import com.ghy.mutiagent.service.route.RouteFactService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * S10 路线事实装配：事实服务（缓存 + 提供方 + 估算降级）、事实工具网关、并行结果版本闸。
 * 真实路线提供方接入前 provider 为空——所有查询走明确标注的 ESTIMATE 降级（学习阶段零外部依赖）。
 */
@Configuration
public class RouteFactConfig {

    @Bean
    public RouteFactService routeFactService(
            @Value("${travel.route.fact-ttl-seconds:1800}") long ttlSeconds) {
        return new RouteFactService(Clock.systemUTC(), ttlSeconds * 1000L);
    }

    @Bean
    public FactToolService factToolService(TravelSessionService sessionService) {
        return new FactToolService(sessionService);
    }

    @Bean
    public FactVersionGate factVersionGate() {
        return new FactVersionGate(0);
    }
}
