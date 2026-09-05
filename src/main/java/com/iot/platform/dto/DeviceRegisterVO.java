package com.iot.platform.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DeviceRegisterVO {

    @JsonProperty("device_key")
    private String deviceKey;

    @JsonProperty("device_name")
    private String deviceName;

    @JsonProperty("device_secret")
    private String deviceSecret;
}
