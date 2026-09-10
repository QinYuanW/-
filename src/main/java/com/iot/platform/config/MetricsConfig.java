package com.iot.platform.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 自定义业务指标：线程池队列水位 + 遥测处理链路耗时。
 * 依赖 Micrometer + Prometheus 注册表。
 */
@Configuration
public class MetricsConfig {

    /**
     * 遥测上报 "接收 → 异步完成" 耗时 Timer（P50/P95/P99 可见）。
     * 注：Controller 同步接收快速返回，异步处理才是实际耗时，所以需要自己埋点。
     * B5 任务要求：关键链路耗时 P50/P95/P99 可观测。
     */
    @Bean
    public Timer telemetryProcessTimer(MeterRegistry registry) {
        return Timer.builder("iot_telemetry_process_duration_seconds")
                .description("Telemetry process duration from receive to DB insert (async processing)")
                .publishPercentileHistogram()  // 生成 histogram → Prometheus 可算 P50/P95/P99
                .register(registry);
    }

    /**
     * 遥测异步线程池队列水位 Gauge：当前队列中等待处理的任务数。
     * 水位超过 80%（5000 容量对应 4000）需告警（B6）。
     */
    @Bean
    public Gauge telemetryQueueWatermark(ThreadPoolTaskExecutor telemetryExecutor, MeterRegistry registry) {
        return Gauge.builder("iot_telemetry_queue_pending", telemetryExecutor, ThreadPoolTaskExecutor::getQueueSize)
                .description("Telemetry async executor pending queue size (capacity 5000)")
                .register(registry);
    }
}