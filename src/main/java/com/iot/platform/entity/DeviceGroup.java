package com.iot.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * E4 设备分组（平铺一级，见 {@code db/create_all.sql} 中的设计说明）。
 *
 * <p>设备归属通过 {@code device.group_id} 表达（一对多）；"多维度标记"由 {@code device.tags} 承担。
 */
@Data
@TableName("device_group")
public class DeviceGroup {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    private Integer sortOrder;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
