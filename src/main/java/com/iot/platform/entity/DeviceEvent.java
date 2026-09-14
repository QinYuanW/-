package com.iot.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * E3 设备事件记录。
 *
 * <p>{@code eventKey} 对应物模型 {@code events[].key}；展示名与类型<b>不入库</b> ——
 * 查询时按产品 TSL 回填，避免 TSL 改名后历史数据与物模型不一致。
 */
@Data
@TableName("device_event")
public class DeviceEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long deviceId;

    private String eventKey;

    /** 事件参数原文（JSON 文本） */
    private String params;

    /** 事件发生时间（设备上报的 ts） */
    private LocalDateTime ts;

    private LocalDateTime createdAt;
}
