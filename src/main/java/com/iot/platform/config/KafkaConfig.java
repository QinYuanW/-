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
 * Consumer 用 MANUAL ack：落库成功才提交 offset，实现「消费成功才确认、不丢在途」。
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

    /** 手动 ack 容器工厂：消费者进程内同步落库成功后才 acknowledge，offset 提交随之发生 */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        // topic 3 个分区 → 并发 3 个消费线程
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        return factory;
    }
}
