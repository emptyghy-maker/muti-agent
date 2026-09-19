package com.ghy.mutiagent.observability.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * Observability 模块 Mapper 注册：不改启动类与业务 @MapperScan，
 * 独立扫描 observability.persistence（业务表 mapper 与 obs_ mapper 分离）。
 */
@Configuration
@MapperScan("com.ghy.mutiagent.observability.persistence")
public class ObservabilityMapperConfig {
}
