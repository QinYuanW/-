package com.iot.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync  // 开启异步任务
public class AsyncConfig {

    // 返回具体类型：MetricsConfig 需绑定线程池队列水位指标
    @Bean("telemetryExecutor")
    public ThreadPoolTaskExecutor telemetryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(5000);
        executor.setThreadNamePrefix("telemetry-");
        executor.initialize();
        return executor;
    }
}