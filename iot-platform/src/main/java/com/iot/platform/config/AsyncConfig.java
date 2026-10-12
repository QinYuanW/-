package com.iot.platform.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 遥测异步线程池。
 * V2-C 优化（BUG-17）：扩并发写 worker、队列扩容、拒绝策略改为「计数丢弃」而非抛异常。
 * 简单总结：这个文件配置的是「遥测数据异步处理线程池」，
 * 重点优化了队列满时的拒绝策略——从抛异常改为静默丢弃 + 监控计数。
 */
@Slf4j
@Configuration
@EnableAsync  // 开启异步任务
public class AsyncConfig {

    // 返回具体类型：MetricsConfig 需绑定线程池队列水位指标
    @Bean("telemetryExecutor")// 配置遥测数据异步处理线程池
    public ThreadPoolTaskExecutor telemetryExecutor(MeterRegistry meterRegistry) {
        Counter rejectedCounter = Counter.builder("iot_telemetry_rejected_total")
                .description("Telemetry tasks rejected at enqueue when async queue is full (silent drop, was invisible before)")
                .register(meterRegistry);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(20000);
        executor.setThreadNamePrefix("telemetry-");
        // 队列满时的降级策略：默认 AbortPolicy 会抛异常→HTTP 5000，且丢弃不可见。
        // 改为「计数 + 日志静默丢弃」：尽力削峰，且丢弃可被 Prometheus 计数观测（BUG-17 §3.6）。
        executor.setRejectedExecutionHandler((r, pool) -> {
            rejectedCounter.increment();
            log.warn("telemetry task rejected (queue full, pool={}), dropped silently", pool.getActiveCount());
        });
        executor.initialize();
        return executor;
    }
}