package com.iot.platform.dto;

import lombok.Data;
import java.util.Map;

@Data
public class CommandRequest {
    private Integer commandType;
    private Map<String, Object> payload;
}