package com.iot.platform.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 告警规则新增 / 编辑请求（E3 尾巴·规则管理 UI，契约 §11）。
 *
 * <p>字段命名对齐全平台约定：多词字段用 {@code @JsonProperty} 收敛成 snake_case
 * （与 {@link CommandRequest} 一致），前端不必为驼峰/下划线做特判。
 */
@Data
public class DeviceRuleRequest {

    private String name;

    @JsonProperty("product_key")
    private String productKey;

    /** 留空 = 作用于该产品下**所有**设备 */
    @JsonProperty("device_key")
    private String deviceKey;

    @JsonProperty("property_key")
    private String propertyKey;

    /** 比较运算符：&gt; &gt;= &lt; &lt;= == != */
    private String operator;

    private BigDecimal threshold;

    /** 1 严重 / 2 一般 / 3 提示 */
    private Integer level;

    @JsonProperty("alarm_type")
    private String alarmType;

    /** 0 仅告警 / 1 告警并下发指令 */
    @JsonProperty("action_type")
    private Integer actionType;

    /** action_type=1 时下发到设备的 payload，必须是 JSON **对象** */
    @JsonProperty("action_payload")
    private String actionPayload;

    /** 抑制窗口（秒）：同规则 + 同设备在窗口内只告警一次 */
    @JsonProperty("cooldown_sec")
    private Integer cooldownSec;

    private Integer enabled;
}
