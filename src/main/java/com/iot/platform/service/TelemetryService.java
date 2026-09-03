package com.iot.platform.service;

import com.iot.platform.dto.TelemetryRequest;

public interface TelemetryService {
    void process(TelemetryRequest request);
}