package com.ghy.mutiagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文冒烟：application-dev.yml 的敏感配置已全部改为无默认值的环境变量占位符，
 * 测试环境不注入真实密钥/数据库，用测试专用占位值补齐（连接均为惰性，启动不触网）。
 */
@SpringBootTest(properties = {
        "jwt.secret=context-test-only-secret-0123456789abcdef",
        "llm.api-key=context-test-only-key",
        "spring.datasource.app.jdbc-url=jdbc:mysql://127.0.0.1:3306/data_agent?useSSL=false",
        "spring.datasource.app.username=test",
        "spring.datasource.app.password=test",
        "redis.host=127.0.0.1"
})
class MutiAgentApplicationTests {

    @Test
    void contextLoads() {
    }

}
