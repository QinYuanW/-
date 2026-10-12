package com.iot.platform.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("product")
public class Product {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String productKey;
    private String name;
    private Long projectId;
    private Integer nodeType;
    private String protocol;
    private String dataFormat;
    private String tsl;
    private Integer status;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}