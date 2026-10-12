package com.iot.platform.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("device_telemetry")
public class DeviceTelemetry {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long deviceId;
    private String propertyKey;
    private String value;
    private Integer valueType;
    private LocalDateTime ts;
}