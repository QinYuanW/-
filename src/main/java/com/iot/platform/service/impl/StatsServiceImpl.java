package com.iot.platform.service.impl;

import com.iot.platform.mapper.StatsMapper;
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

    @Override
    public Map<String, Object> overview() {
        long productTotal = statsMapper.countProducts();
        long productEnabled = statsMapper.countProductsEnabled();

        long deviceTotal = statsMapper.countDevices();
        long deviceOnline = statsMapper.countDevicesOnline();
        long deviceEnabled = statsMapper.countDevicesEnabled();

        // 带 ts 条件的两个计数可走分区裁剪；total 是全表扫描（唯一重查询）
        String monthStart = LocalDate.now().withDayOfMonth(1).format(DAY);
        String todayStart = LocalDate.now().format(DAY);

        Map<String, Object> result = new LinkedHashMap<>();

        result.put("product", Map.of(
                "total", productTotal,
                "enabled", productEnabled,
                "disabled", productTotal - productEnabled));

        result.put("device", Map.of(
                "total", deviceTotal,
                "online", deviceOnline,
                "offline", deviceTotal - deviceOnline,
                "enabled", deviceEnabled,
                "disabled", deviceTotal - deviceEnabled));

        result.put("message", Map.of(
                "total", statsMapper.countAllTelemetry(),
                "month", statsMapper.countTelemetrySince(monthStart),
                "today", statsMapper.countTelemetrySince(todayStart)));

        // 告警链路依赖 E3，本阶段恒为 0（不是缺陷，见实施方案 §0）
        result.put("alarm", Map.of("total", 0, "month", 0, "today", 0));

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
        // E6 阶段空壳：结构与 API契约 §7.3 对齐，数据写入待 E3
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", 0);
        result.put("types", List.of());
        result.put("items", List.of());
        return result;
    }
}
