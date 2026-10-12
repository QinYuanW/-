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

    /** F2 启用状态：1 启用 / 0 停用（停用后立即拒绝 MQTT 接入，但数据全保留） */
    private Integer status;

    /**
     * 所属产品是否启用（只读派生字段，由 Service 层填充，非数据库列）。
     *
     * <p>用于让前端区分「设备自己被停用」与「所属产品被停用、设备被连带拒绝」——
     * 二者在链路上都会被拒绝接入，但**原因与恢复动作完全不同**：
     * 前者去设备页启用这台设备，后者去产品页恢复产品。
     *
     * <p>若前端只看 {@link #status}，产品停用后其下设备会显示成"一切正常" ——
     * 属于典型的「界面说谎」（执行层知道、展示层不知道）。
     */
    @JsonProperty("product_enabled")
    private Boolean productEnabled;

    // ── ② DeviceVO 从 Device 实体转换而来──── 专门负责裁剪的静态工厂方法
    public static DeviceVO from(Device d) {
        DeviceVO vo = new DeviceVO();
        vo.setId(d.getId());
        vo.setDeviceKey(d.getDeviceKey());
        vo.setDeviceName(d.getDeviceName());
        vo.setProductKey(d.getProductKey());
        vo.setOnlineStatus(d.getOnlineStatus());
        vo.setStatus(d.getStatus());
        vo.setLastOnlineAt(d.getLastOnlineAt() != null ? d.getLastOnlineAt().toString() : null);
        return vo;
    }
}
