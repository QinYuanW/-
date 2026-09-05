package com.iot.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "emqx")
public class EmqxProperties {

    private String apiUrl = "http://localhost:18083/api/v5";

    private String username = "admin";

    private String password = "public";
}
