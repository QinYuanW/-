package com.iot.platform.dto;

import com.iot.platform.entity.Device;
import lombok.Data;

@Data
public class DeviceVO {
    private Long id;
    private String deviceKey;
    private String deviceName;
    private String productKey;
    private Integer onlineStatus;
    private String lastOnlineAt;

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