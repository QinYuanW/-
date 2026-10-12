package com.iot.platform.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("project")
public class Project {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String description;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}