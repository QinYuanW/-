package com.iot.platform.service;

import com.iot.platform.dto.CommandRequest;
import com.iot.platform.dto.CommandVO;

public interface CommandService {
    CommandVO dispatch(String deviceKey, CommandRequest request);
    CommandVO getStatus(String requestId);
}