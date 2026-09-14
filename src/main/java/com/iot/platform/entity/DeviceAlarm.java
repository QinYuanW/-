package com.iot.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * E3 告警记录。
 *
 * <p>独立成表而不复用 {@code device_log}：告警有结构化字段与「未处理→已处理」状态流转，
 * 塞进 1024 字符自由文本会让筛选与统计退化成字符串解析。
 */
@Data
@TableName("device_alarm")
public class DeviceAlarm {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long deviceId;

    private Long ruleId;

    /** 告警类型（规则告警 / 系统告警） */
    private String alarmType;

    /** 级别：1 严重 / 2 一般 / 3 提示 */
    private Integer level;

    private String ruleName;

    /** 告警正文（含命中值与阈值，便于事后追溯） */
    private String content;

    /** 处理状态：0 未处理 / 1 已处理 */
    private Integer status;

    private LocalDateTime handledAt;

    private LocalDateTime createdAt;
}
