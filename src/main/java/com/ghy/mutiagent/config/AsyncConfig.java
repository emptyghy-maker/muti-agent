package com.ghy.mutiagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * SSE 流式请求专用线程池。
 *
 * 流式模型是异步回调（TokenStream.start() 立即返回，token 在后台线程陆续到达），
 * 需要把整条流水线放到独立线程里执行，控制器才能立刻返回 SseEmitter 开始推送。
 */
@Configuration
public class AsyncConfig {

    @Bean
    public ThreadPoolTaskExecutor chatStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("chat-stream-");
        executor.initialize();
        return executor;
    }
}
