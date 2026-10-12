package com.iot.platform.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.Map;

@Data
public class TelemetryRequest {
    @JsonProperty("device_key") // 设备key
    private String deviceKey;
    private Long ts;
    private Map<String, Object> properties;
}