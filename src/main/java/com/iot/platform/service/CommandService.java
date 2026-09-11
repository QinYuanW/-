package com.iot.platform.service;

import com.iot.platform.dto.CommandRequest;
import com.iot.platform.dto.CommandVO;

import java.util.List;

public interface CommandService {
    /**
     * 发送指令
     * @param deviceKey 设备key
     * @param request 指令请求
     * @return 指令VO
     */
    CommandVO dispatch(String deviceKey, CommandRequest request);
    /**
     * 获取指令状态
     * @param requestId 指令id
     * @return 指令VO
     */
    CommandVO getStatus(String requestId);

    /**
     * 指令确认
     * @param requestId 指令id
     * @param success 是否成功确认
     * @return 指令VO
     */
    CommandVO ack(String requestId, boolean success);

    /**
     * E1 设备影子：设备上线时补发此前因离线而挂起的指令（{@code status=0}）。
     *
     * <p>走既有索引 {@code idx_device_status_time (device_id, status, created_at)}，按创建顺序补发；
     * 单轮补发有上限（防「补发风暴」），超期未发（&gt; 24h）的指令直接置 4（超时）。
     *
     * @return 本轮实际补发（publish 成功）的条数
     */
    int replayPending(Long deviceId, String deviceKey);

    /** E1：查询某设备的待下发指令（{@code status=0}），供控制台展示离线队列 */
    List<CommandVO> listPending(String deviceKey);
}