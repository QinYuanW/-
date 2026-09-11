package com.iot.platform.service;

import java.util.Map;

/**
 * E1 设备影子同步编排：把「设备上线」这一事件翻译成两个动作。
 *
 * <p>拆成独立服务（而非塞进 TelemetryServiceImpl）是为了保持域边界：
 * 遥测链路不需要了解指令补发与影子对齐的细节。
 *
 * <p>实现方必须**自行吞掉异常**——设备上线事件已在遥测链路中处理完毕，
 * 影子同步失败不得回溯影响遥测入库。
 */
public interface ShadowSyncService {

    /**
     * 设备上线时同步影子：
     * <ol>
     *   <li><b>补发离线指令</b>：把此前因离线挂起的 {@code status=0} 指令按创建顺序下发；</li>
     *   <li><b>对齐期望值</b>：把 desired 与 reported 的差异（delta）作为属性设置指令下发。</li>
     * </ol>
     *
     * @param deviceId  设备主键
     * @param deviceKey 设备 key（构造下行 topic 用）
     */
    void onDeviceOnline(Long deviceId, String deviceKey);

    /**
     * 写入期望值并尝试下发差异。
     *
     * <p>设备在线 → 指令立即下发（command_status=1）；设备离线 → 指令挂起为
     * {@code status=0}，等设备上线由 {@link #onDeviceOnline} 自动补发。
     *
     * @return {@code {accepted, delta, command_request_id, command_status}}
     */
    Map<String, Object> applyDesired(String deviceKey, Map<String, Object> desired);
}
