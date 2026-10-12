package com.iot.platform.controller;

import com.iot.platform.common.Result;
import com.iot.platform.dto.DeviceGroupRequest;
import com.iot.platform.service.DeviceGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * E4 设备分组接口（契约 §10）。
 *
 * <p>批量分配用 {@code PUT /device-groups/{id}/devices}，{@code id=0} 表示「移出分组」——
 * 这样"移入某组"和"移出分组"共用同一端点，前端不必区分两个接口。
 */
@RestController
@RequestMapping("/api/v1/device-groups")
@RequiredArgsConstructor
public class DeviceGroupController {

    private final DeviceGroupService groupService;

    /** 分组列表（含各分组设备数与未分组设备数） */
    @GetMapping
    public Result<Map<String, Object>> list() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ungrouped", groupService.countUngrouped());
        data.put("items", groupService.listWithCount());
        return Result.ok(data);
    }

    /** 新建分组 */
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody DeviceGroupRequest request) {
        Long id = groupService.create(request);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        return Result.ok(data);
    }

    /** 编辑分组 */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody DeviceGroupRequest request) {
        groupService.update(id, request);
        return Result.ok();
    }

    /** 删除分组（组内设备回到「未分组」，不级联删设备） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        groupService.delete(id);
        return Result.ok();
    }

    /** 批量分配设备到分组（id=0 表示移出分组） */
    @PutMapping("/{id}/devices")
    public Result<Map<String, Object>> assign(@PathVariable Long id,
                                              @RequestBody Map<String, List<String>> body) {
        int affected = groupService.assignDevices(body.get("device_keys"), id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("affected", affected);
        return Result.ok(data);
    }
}
