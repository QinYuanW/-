package com.iot.platform.service.impl;

import com.iot.platform.entity.DeviceAlarm;
import com.iot.platform.mapper.DeviceAlarmMapper;
import com.iot.platform.service.AlarmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * E3 告警服务实现。
 *
 * <p>面向契约 §7.3 输出：级别与状态以<b>中文文案</b>返回（"严重/一般/提示"、"未处理/已处理"），
 * 库内仍存整型，便于索引与筛选。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlarmServiceImpl implements AlarmService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Map<Integer, String> LEVEL_TEXT = Map.of(1, "严重", 2, "一般", 3, "提示");
    private static final String[] STATUS_TEXT = {"未处理", "已处理"};

    private final DeviceAlarmMapper alarmMapper;

    @Override
    public DeviceAlarm raise(DeviceAlarm alarm) {
        if (alarm.getCreatedAt() == null) alarm.setCreatedAt(LocalDateTime.now());
        if (alarm.getStatus() == null) alarm.setStatus(0);
        if (alarm.getAlarmType() == null) alarm.setAlarmType("规则告警");
        alarmMapper.insert(alarm);
        return alarm;
    }

    @Override
    public Map<String, Object> page(Integer page, Integer size, Integer level, Integer status) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 100);

        List<Map<String, Object>> rows = alarmMapper.selectPage(level, status, (p - 1) * s, s);

        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.get("id"));
            item.put("alarm_type", r.get("alarmType"));
            item.put("level", LEVEL_TEXT.getOrDefault(((Number) r.get("level")).intValue(), "一般"));
            item.put("rule_name", r.get("ruleName"));
            item.put("content", r.get("content"));
            item.put("product_key", r.get("productKey"));
            item.put("device_key", r.get("deviceKey"));

            int st = ((Number) r.get("status")).intValue();
            item.put("status", st >= 0 && st < STATUS_TEXT.length ? STATUS_TEXT[st] : "未处理");

            Object ca = r.get("createdAt");
            item.put("alarm_at", ca instanceof LocalDateTime
                    ? ((LocalDateTime) ca).format(TS)
                    : String.valueOf(ca).replace('T', ' '));
            items.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", alarmMapper.countByFilter(level, status));
        result.put("types", alarmMapper.countByType(level, status));
        result.put("items", items);
        return result;
    }

    @Override
    public boolean handle(Long id) {
        DeviceAlarm update = new DeviceAlarm();
        update.setId(id);
        update.setStatus(1);
        update.setHandledAt(LocalDateTime.now());
        return alarmMapper.updateById(update) > 0;
    }
}
