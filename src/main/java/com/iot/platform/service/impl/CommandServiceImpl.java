package com.iot.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.iot.platform.dto.CommandRequest;
import com.iot.platform.dto.CommandVO;
import com.iot.platform.entity.Command;
import com.iot.platform.entity.Device;
import com.iot.platform.mapper.CommandMapper;
import com.iot.platform.mapper.DeviceMapper;
import com.iot.platform.service.CommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommandServiceImpl implements CommandService {

    private final CommandMapper commandMapper;
    private final DeviceMapper deviceMapper;

    @Override
    public CommandVO dispatch(String deviceKey, CommandRequest request) {
        Device device = deviceMapper.selectOne(
                new LambdaQueryWrapper<Device>().eq(Device::getDeviceKey, deviceKey));
        if (device == null) {
            throw new IllegalArgumentException("device not found");
        }

        Command cmd = new Command();
        cmd.setDeviceId(device.getId());
        cmd.setCommandType(request.getCommandType());
        cmd.setPayload(request.getPayload() != null ? request.getPayload().toString() : "{}");
        cmd.setStatus(0);
        cmd.setRequestId(UUID.randomUUID().toString());
        cmd.setCreatedAt(LocalDateTime.now());
        cmd.setUpdatedAt(LocalDateTime.now());
        commandMapper.insert(cmd);

        log.info("command saved: request_id={}, device_key={}", cmd.getRequestId(), deviceKey);
        return CommandVO.from(cmd);
    }

    @Override
    public CommandVO getStatus(String requestId) {
        Command cmd = commandMapper.selectOne(
                new LambdaQueryWrapper<Command>().eq(Command::getRequestId, requestId));
        return cmd != null ? CommandVO.from(cmd) : null;
    }
}