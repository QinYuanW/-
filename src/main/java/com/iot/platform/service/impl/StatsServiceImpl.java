package com.iot.platform.service.impl;

import com.iot.platform.mapper.DeviceAlarmMapper;
import com.iot.platform.mapper.StatsMapper;
import com.iot.platform.service.AlarmService;
import com.iot.platform.service.StatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * E6 控制台统计服务实现。
 *
 * <p>设计取舍（2026-09-14 实测驱动）：全表 {@code COUNT(*)} 在 44 万行下净耗时约 0.25s，
 * 因此<b>不引入缓存与 Redis 计数器</b>——先不做没有数据支撑的优化（项目铁律 2）。
 * 若后续数据量增长导致 RT 恶化，再依据 D5 压测数据决定是否上缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatsServiceImpl implements StatsService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final StatsMapper statsMapper;
    private final DeviceAlarmMapper alarmMapper;
    private final AlarmService alarmService;

    @Override
    public Map<String, Object> overview() {
        long productTotal = statsMapper.countProducts();
        long productEnabled = statsMapper.countProductsEnabled();

        long deviceTotal = statsMapper.countDevices();
        long deviceOnline = statsMapper.countDevicesOnline();
        long deviceEnabled = statsMapper.countDevicesEnabled();
        // 可用性口径（2026-09-15）：产品停用会级联废掉整条产品线的设备，
        // 但那些设备自身 status 仍是 1 —— 只报「设备自身停用数」会让它们整体隐身。
        long deviceAvailable = statsMapper.countDevicesAvailable();
        long deviceBlockedByProduct = statsMapper.countDevicesBlockedByProduct();

        // 带 ts 条件的两个计数可走分区裁剪；total 是全表扫描（唯一重查询）
        String monthStart = LocalDate.now().withDayOfMonth(1).format(DAY);
        String todayStart = LocalDate.now().format(DAY);

        Map<String, Object> result = new LinkedHashMap<>();

        result.put("product", Map.of(
                "total", productTotal,
                "enabled", productEnabled,
                "disabled", productTotal - productEnabled));

        // device 段口径（2026-09-15 调整）：
        //  · enabled / disabled  —— 设备**自身**配置态（保留原义，向后兼容）
        //  · unavailable        —— 真实不可用 = 自身停用 + 被产品停用牵连
        //  · blocked_by_product —— unavailable 中「设备自己没停、纯属被产品拖累」的那部分
        //  三者满足：unavailable = disabled + blocked_by_product
        result.put("device", Map.of(
                "total", deviceTotal,
                "online", deviceOnline,
                "offline", deviceTotal - deviceOnline,
                "enabled", deviceEnabled,
                "disabled", deviceTotal - deviceEnabled,
                "unavailable", deviceTotal - deviceAvailable,
                "blocked_by_product", deviceBlockedByProduct));

        result.put("message", Map.of(
                "total", statsMapper.countAllTelemetry(),
                "month", statsMapper.countTelemetrySince(monthStart),
                "today", statsMapper.countTelemetrySince(todayStart)));

        // E3 起接入真实告警（device_alarm），口径与消息一致：全量 / 本月 / 今日
        result.put("alarm", Map.of(
                "total", alarmMapper.countAll(),
                "month", alarmMapper.countSince(monthStart),
                "today", alarmMapper.countSince(todayStart)));

        return result;
    }

    @Override
    public Map<String, Object> messageTrend(String granularity, Integer range) {
        boolean byMonth = "month".equalsIgnoreCase(granularity);
        int n = (range == null || range <= 0)
                ? (byMonth ? 12 : 30)
                : Math.min(range, byMonth ? 24 : 90);

        String start = byMonth
                ? LocalDate.now().minusMonths(n - 1L).withDayOfMonth(1).format(DAY)
                : LocalDate.now().minusDays(n - 1L).format(DAY);

        List<Map<String, Object>> rows = byMonth
                ? statsMapper.countTelemetryByMonth(start)
                : statsMapper.countTelemetryByDay(start);

        // 只返回有数据的桶，缺失由前端补 0 —— 后端不构造空桶，保持接口"笨"
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("bucket", row.get("bucket"));
            item.put("message", ((Number) row.get("cnt")).longValue());
            item.put("alarm", 0);   // 依赖 E3
            items.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("granularity", byMonth ? "month" : "day");
        result.put("items", items);
        return result;
    }

    @Override
    public Map<String, Object> alerts(Integer page, Integer size) {
        // E3 起接入真实告警数据（结构与 API契约 §7.3 一致）
        return alarmService.page(page, size, null, null);
    }
}
