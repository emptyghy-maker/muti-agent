package com.ghy.mutiagent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * 启动类（多 Agent 旅游攻略规划）。
 *
 * 1. {@code @MapperScan("com.ghy.mutiagent.repository.mapper")} 扫描旅游业务 Mapper 接口；
 * 2. {@code exclude = DataSourceAutoConfiguration.class} 关闭默认「单数据源」自动配置，
 *    数据源由 config 包里的 AppDataSourceConfig 手动定义。
 */
@MapperScan("com.ghy.mutiagent.repository.mapper")
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
public class MutiAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(MutiAgentApplication.class, args);
    }

}
