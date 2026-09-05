package com.iot.platform.controller;

import com.iot.platform.common.Result;
import com.iot.platform.dto.CommandAckRequest;
import com.iot.platform.dto.CommandRequest;
import com.iot.platform.dto.CommandVO;
import com.iot.platform.service.CommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CommandController {

    private final CommandService commandService;

    @PostMapping("/devices/{deviceKey}/commands")
    public Result<CommandVO> dispatch(@PathVariable String deviceKey,
                                      @RequestBody CommandRequest request) {
        try {
            return Result.ok(commandService.dispatch(deviceKey, request));
        } catch (IllegalArgumentException e) {
            return Result.fail(4004, e.getMessage());
        }
    }

    @GetMapping("/commands/{requestId}")
    public Result<CommandVO> status(@PathVariable String requestId) {
        CommandVO vo = commandService.getStatus(requestId);
        return vo != null ? Result.ok(vo) : Result.fail(4004, "command not found");
    }

    @PostMapping("/commands/ack")
    public Result<CommandVO> ack(@RequestBody CommandAckRequest request) {
        if (request.getRequestId() == null) {
            return Result.fail(4001, "request_id is required");
        }
        boolean success = request.getSuccess() == null || request.getSuccess();
        CommandVO vo = commandService.ack(request.getRequestId(), success);
        return vo != null ? Result.ok(vo) : Result.fail(4004, "command not found");
    }
}