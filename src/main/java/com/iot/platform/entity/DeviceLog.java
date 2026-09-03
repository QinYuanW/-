package com.iot.platform.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("device_log")
public class DeviceLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long deviceId;
    private Integer logType;
    private String content;
    private LocalDateTime createdAt;
}