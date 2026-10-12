package com.iot.platform.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DeviceRegisterRequest {

    @NotBlank(message = "product_key 不能为空")
    @JsonProperty("product_key")
    private String productKey;

    @JsonProperty("device_name")
    private String deviceName;

    @JsonProperty("device_key")
    private String deviceKey;

    @Min(value = 1, message = "count 至少为 1")
    @Max(value = 1000, message = "单次批量注册最多 1000 台")
    private Integer count = 1;

    @JsonProperty("name_prefix")
    private String namePrefix;
}
