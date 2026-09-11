package com.iot.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * D1 Kafka 配置：ProducerFactory + KafkaTemplate（EMQX webhook 回调 → Controller → send to Kafka）
 *
 * <p>D2-2 变更：消费端 ack 由 MANUAL 改为容器托管（RECORD）。
 * ① {@code @RetryableTopic} 不支持手动 ack（重试/DLT 容器依赖容器托管提交）；
 * ② 手动 ack 本身就是 BUG-21 静默丢数据的成因（提交的是分区位点，会越过未 ack 的失败记录）。
 * 「不丢」改由「非阻塞重试阶梯 + 死信隔离 + D2-1 唯一键幂等」共同保证。
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    /** 遥测 topic / 消费者组 */
    public static final String TOPIC = "iot.telemetry";
    public static final String CONSUMER_GROUP = "iot-telemetry-consumers";

    @Bean
    public ProducerFactory<String, String> producerFactory(
            org.springframework.boot.autoconfigure.kafka.KafkaProperties kafkaProperties) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(null));
        // 最重要：acks=all 确认写到 leader + all isr，broker 确认才算持久化完成
        props.put("acks", "all");
        return new DefaultKafkaProducerFactory<>(props, new StringSerializer(), new StringSerializer());
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    /**
     * 消费容器工厂：topic 3 个分区 → 3 个并发消费线程。
     * ack 模式取容器托管的 RECORD（每条记录处理成功即提交；失败则转交重试 topic，主分区照常推进），
     * 这同时满足 {@code @RetryableTopic} 的要求并根除 BUG-21 的"越过"问题。
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        // topic 3 个分区 → 并发 3 个消费线程
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }
}
