package com.iot.platform.controller;

import com.iot.platform.common.Result;
import com.iot.platform.dto.TelemetryRequest;
import com.iot.platform.service.TelemetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TelemetryController {

    private final TelemetryService telemetryService;

    @PostMapping("/telemetry")
    public Result<Void> receive(@RequestBody TelemetryRequest request) {
        telemetryService.process(request);
        return Result.ok();
    }
}