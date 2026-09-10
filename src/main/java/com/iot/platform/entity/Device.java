package com.iot.platform.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("device") // → 数据库表名
public class Device {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String deviceKey;
    private String productKey;
    private String deviceName;
    private String deviceSecret;
    private Long projectId;
    private Integer onlineStatus;
    private LocalDateTime lastOnlineAt;
    private LocalDateTime lastOfflineAt;
    private String lastIp;
    private Long groupId;
    private String tags;
    private Integer status;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}