package com.iot.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "emqx")   // 配置属性前缀，用于绑定 application.yml 中的 emqx 配置
public class EmqxProperties {

    private String apiUrl = "http://localhost:18083/api/v5";

    private String username = "admin";

    private String password = "public";
}
