package com.iot.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * E4 设备分组（平铺一级，见 {@code db/schema.sql} 中该表的设计说明）。
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

    /**
     * 墓碑标记：{@code 0} = 存活；{@code = id} 表示已删除。
     *
     * <p>用 id 而非 1，是为了配合唯一键 {@code uk_group_name (name, deleted)}，让「删除后名称可复用」
     * 对**任意多次**删除都成立 —— 用 0/1 时第二次删除同名分组会撞唯一键（实测 1062）。
     * 删除动作由 {@code DeviceGroupServiceImpl.delete()} 手工 UPDATE 完成，不走 MP 的 deleteById。
     */
    @TableLogic(value = "0", delval = "id")
    private Long deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
