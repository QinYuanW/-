package com.iot.platform.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class CommandAckRequest {

    @JsonProperty("request_id")
    private String requestId;

    private Boolean success;
}
