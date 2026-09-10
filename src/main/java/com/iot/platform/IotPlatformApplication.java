package com.iot.platform;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 物联网 PaaS 云平台 MVP 启动类
 */
@SpringBootApplication // 组合注解：自动配置 + 组件扫描 + 声明这是 Spring Boot 应用
@MapperScan("com.iot.platform.mapper") // 扫描 Mapper 接口，注册为 Bean
@EnableScheduling // 开启定时任务支持（@Scheduled 生效的前提）
public class IotPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(IotPlatformApplication.class, args);// 启动内嵌 Tomcat
    }
}
