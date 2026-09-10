package com.iot.platform.service;

import com.iot.platform.dto.CommandRequest;
import com.iot.platform.dto.CommandVO;

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
}