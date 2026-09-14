package com.iot.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * E3 阈值规则定义。
 *
 * <p>命中后生成告警；{@code actionType=1} 时还会联动下发指令（如「温度 &gt; 50 自动关断」）。
 * {@code cooldownSec} 是抑制窗口 —— 温度持续超标时若不抑制，每秒一条上报会刷爆告警表。
 */
@Data
@TableName("device_rule")
public class DeviceRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String productKey;

    /** null = 作用于该产品下所有设备 */
    private String deviceKey;

    private String propertyKey;

    /** 比较运算符：&gt; &gt;= &lt; &lt;= == != */
    private String operator;

    private BigDecimal threshold;

    /** 1 严重 / 2 一般 / 3 提示 */
    private Integer level;

    private String alarmType;

    /** 0 仅告警 / 1 告警并下发指令 */
    private Integer actionType;

    /** actionType=1 时下发到设备的 payload（JSON 文本） */
    private String actionPayload;

    /** 抑制窗口（秒）：同规则 + 同设备在窗口内只告警一次 */
    private Integer cooldownSec;

    private Integer enabled;

    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
