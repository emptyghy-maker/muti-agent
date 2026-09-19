package com.ghy.mutiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * 业务数据源配置。
 *
 * {@code @Primary} 很关键：本项目有两个 DataSource Bean（业务 + 只读），
 * 当 Spring/MyBatis-Plus 需要自动注入一个数据源时，默认用这个 @Primary 的，
 * 否则会报「找到多个 DataSource，不知道用哪个」。
 */
@Configuration
public class AppDataSourceConfig {

    /** 业务数据源：读 application-dev.yml 里 spring.datasource.app 下的配置 */
    @Primary
    @Bean(name = "appDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.app")
    public DataSource appDataSource() {
        return DataSourceBuilder.create().build();
    }
}
