package com.iot.platform.service;

import com.iot.platform.dto.DeviceGroupRequest;

import java.util.List;
import java.util.Map;

/** E4 设备分组服务。 */
public interface DeviceGroupService {

    /** 分组列表（含各分组设备数，一次查询无 N+1） */
    List<Map<String, Object>> listWithCount();

    /** 未分组设备数 */
    long countUngrouped();

    /** 新建分组，返回新 id */
    Long create(DeviceGroupRequest request);

    /** 编辑分组 */
    void update(Long id, DeviceGroupRequest request);

    /**
     * 删除分组（软删除）。
     *
     * <p><b>不级联删除设备</b>：组内设备的 {@code group_id} 置空（回到"未分组"），
     * 避免"删个分组把设备一起干掉"这种运维事故。
     */
    void delete(Long id);

    /**
     * 批量设置设备分组：把 {@code deviceKeys} 指向的设备移动到 {@code groupId}。
     *
     * @param groupId 传 {@code null} 或 0 表示移出分组（回到"未分组"）
     * @return 实际影响的设备数
     */
    int assignDevices(List<String> deviceKeys, Long groupId);
}
