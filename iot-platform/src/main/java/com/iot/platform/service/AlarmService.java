package com.iot.platform.service;

import com.iot.platform.entity.DeviceAlarm;

import java.util.Map;

/**
 * E3 告警服务：告警写入、查询与处理。
 */
public interface AlarmService {

    /**
     * 写一条告警。
     *
     * @return 落库后的告警（含自增 id）
     */
    DeviceAlarm raise(DeviceAlarm alarm);

    /**
     * 分页查询告警（含 device_key / product_key）。
     *
     * @param level  级别筛选（1/2/3），null 不过滤
     * @param status 状态筛选（0 未处理 / 1 已处理），null 不过滤
     */
    Map<String, Object> page(Integer page, Integer size, Integer level, Integer status);

    /** 标记为已处理 */
    boolean handle(Long id);
}
