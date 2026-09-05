package com.iot.platform.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.Map;

@Data
public class CommandRequest {

    @JsonProperty("command_type")
    private Integer commandType;

    private Map<String, Object> payload;
}
