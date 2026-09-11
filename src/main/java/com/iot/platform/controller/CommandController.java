package com.iot.platform.controller;

import com.iot.platform.common.Result;
import com.iot.platform.dto.CommandAckRequest;
import com.iot.platform.dto.CommandRequest;
import com.iot.platform.dto.CommandVO;
import com.iot.platform.service.CommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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

    /**
     * E1：查询某设备的「待下发」指令（{@code status=0}）—— 即离线指令队列。
     *
     * <p>设备离线时下发的指令不再谎报已下发，而是挂起在这里；设备上线后自动补发。
     */
    @GetMapping("/devices/{deviceKey}/commands/pending")
    public Result<Map<String, Object>> pending(@PathVariable String deviceKey) {
        List<CommandVO> items = commandService.listPending(deviceKey);
        return Result.ok(Map.of("total", items.size(), "items", items));
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