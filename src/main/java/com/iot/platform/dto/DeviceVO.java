package com.iot.platform.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.iot.platform.entity.Device;
import lombok.Data;

//	设备响应体
@Data
public class DeviceVO {

    private Long id;

    @JsonProperty("device_key")
    private String deviceKey;

    @JsonProperty("device_name")
    private String deviceName;

    @JsonProperty("product_key")
    private String productKey;

    @JsonProperty("online_status")
    private Integer onlineStatus;

    @JsonProperty("last_online_at")
    private String lastOnlineAt;

    // ── ② DeviceVO 从 Device 实体转换而来──── 专门负责裁剪的静态工厂方法
    public static DeviceVO from(Device d) {
        DeviceVO vo = new DeviceVO();
        vo.setId(d.getId());
        vo.setDeviceKey(d.getDeviceKey());
        vo.setDeviceName(d.getDeviceName());
        vo.setProductKey(d.getProductKey());
        vo.setOnlineStatus(d.getOnlineStatus());
        vo.setLastOnlineAt(d.getLastOnlineAt() != null ? d.getLastOnlineAt().toString() : null);
        return vo;
    }
}
