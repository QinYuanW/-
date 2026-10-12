package com.iot.platform.dto;

import lombok.Data;

@Data
public class TelemetryPointVO {
    private Long ts;
    private String value;
}