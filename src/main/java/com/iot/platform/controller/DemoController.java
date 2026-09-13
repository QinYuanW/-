package com.iot.platform.controller;

import com.iot.platform.common.Result;
import com.iot.platform.service.ShadowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * G5 演示数据一键重置：清空遥测/日志/指令，物理删除全部设备与产品，重建演示基线
 * （项目「演示项目」+ 产品 p_demo_001 + 设备 dev_demo_001，对齐 db/seed.sql）。
 *
 * <p>说明：事件流表（telemetry/log/command）与业务表（device/product）在删除设备时
 * 需要物理删除（MyBatis-Plus 的 delete 会走 @TableLogic 软删，残留行会挡住重建），
 * 故这里用 JdbcTemplate 原生 SQL 执行；影子/鉴权/映射等 Redis 缓存逐一清理。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DemoController {

    private static final String DEMO_TSL = "{\"properties\":["
            + "{\"key\":\"temperature\",\"name\":\"温度\",\"type\":\"float\",\"access\":\"readonly\",\"unit\":\"℃\"},"
            + "{\"key\":\"humidity\",\"name\":\"湿度\",\"type\":\"float\",\"access\":\"readonly\",\"unit\":\"%\"},"
            + "{\"key\":\"switch\",\"name\":\"开关\",\"type\":\"bool\",\"access\":\"readwrite\"}],"
            + "\"services\":[],\"events\":[{\"key\":\"high_temp_alarm\",\"name\":\"高温告警\"}]}";

    private final JdbcTemplate jdbcTemplate;
    private final ShadowService shadowService;
    private final StringRedisTemplate redisTemplate;

    @PostMapping("/demo/reset")
    public Result<Map<String, Object>> reset() {
        List<String> deviceKeys = jdbcTemplate.queryForList(
                "SELECT device_key FROM device WHERE deleted = 0", String.class);

        // 1. 事件流表整表清空（演示场景，无需保留历史）
        jdbcTemplate.update("DELETE FROM device_telemetry");
        jdbcTemplate.update("DELETE FROM device_log");
        jdbcTemplate.update("DELETE FROM command");

        // 2. 物理删除设备与产品（软删行会挡住 id=1 重建，必须硬删）
        for (String deviceKey : deviceKeys) {
            shadowService.clear(deviceKey);
            redisTemplate.delete(List.of(
                    "auth:" + deviceKey,
                    "did:" + deviceKey,
                    "shadow:tsl:" + deviceKey));
        }
        jdbcTemplate.update("DELETE FROM device");
        jdbcTemplate.update("DELETE FROM product");

        // 3. 重建演示基线（id 固定为 1，对齐 seed.sql 的 ON DUPLICATE 语义）
        jdbcTemplate.update("INSERT INTO project (id, name, description) VALUES (1, '演示项目', 'MVP 联调默认项目') "
                + "ON DUPLICATE KEY UPDATE name = VALUES(name)");
        jdbcTemplate.update("INSERT INTO product (id, product_key, name, project_id, node_type, protocol, data_format, tsl, status) "
                + "VALUES (1, 'p_demo_001', '温湿度传感器', 1, 1, 'MQTT', 'JSON', ?, 1)", DEMO_TSL);
        jdbcTemplate.update("INSERT INTO device (id, device_key, product_key, device_name, device_secret, project_id, online_status, status) "
                + "VALUES (1, 'dev_demo_001', 'p_demo_001', '客厅温湿度计', 'demo_secret_123', 1, 0, 1)");

        log.info("demo reset done: devices_cleared={}, baseline restored", deviceKeys.size());
        return Result.ok(Map.of("cleared", deviceKeys.size()));
    }
}
