package com.iot.platform.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("command")
public class Command {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long deviceId;
    private Integer commandType;
    private String payload;
    private Integer status;
    private String requestId;
    private LocalDateTime sentAt;
    private LocalDateTime ackAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}