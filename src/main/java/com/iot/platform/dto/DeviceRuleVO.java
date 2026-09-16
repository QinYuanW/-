package com.iot.platform.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.iot.platform.entity.DeviceRule;
import lombok.Data;

import java.math.BigDecimal;

/** 告警规则响应体（E3 尾巴·规则管理 UI）。 */
@Data
public class DeviceRuleVO {

    private Long id;

    private String name;

    @JsonProperty("product_key")
    private String productKey;

    /** 空 = 作用于该产品下所有设备 */
    @JsonProperty("device_key")
    private String deviceKey;

    @JsonProperty("property_key")
    private String propertyKey;

    private String operator;

    private BigDecimal threshold;

    private Integer level;

    @JsonProperty("alarm_type")
    private String alarmType;

    @JsonProperty("action_type")
    private Integer actionType;

    @JsonProperty("action_payload")
    private String actionPayload;

    @JsonProperty("cooldown_sec")
    private Integer cooldownSec;

    private Integer enabled;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;

    /**
     * 该规则累计触发的告警条数（派生字段，非数据库列）。
     *
     * <p>列表页用它回答"这条规则到底有没有在工作" —— 规则配错（物模型 key 写错、
     * 阈值方向反了）的典型症状就是「启用中但一条告警都没产生过」，
     * 只看 enabled 开关是看不出来的。
     */
    @JsonProperty("alarm_count")
    private Long alarmCount;

    public static DeviceRuleVO from(DeviceRule r) {
        DeviceRuleVO vo = new DeviceRuleVO();
        vo.setId(r.getId());
        vo.setName(r.getName());
        vo.setProductKey(r.getProductKey());
        vo.setDeviceKey(r.getDeviceKey());
        vo.setPropertyKey(r.getPropertyKey());
        vo.setOperator(r.getOperator());
        vo.setThreshold(r.getThreshold());
        vo.setLevel(r.getLevel());
        vo.setAlarmType(r.getAlarmType());
        vo.setActionType(r.getActionType());
        vo.setActionPayload(r.getActionPayload());
        vo.setCooldownSec(r.getCooldownSec());
        vo.setEnabled(r.getEnabled());
        vo.setCreatedAt(r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
        vo.setUpdatedAt(r.getUpdatedAt() != null ? r.getUpdatedAt().toString() : null);
        vo.setAlarmCount(0L);
        return vo;
    }
}
