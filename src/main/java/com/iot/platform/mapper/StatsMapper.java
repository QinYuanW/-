package com.iot.platform.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * E6 控制台统计查询。
 *
 * <p>仪表盘所需的聚合 SQL 集中在此，避免污染各业务 Mapper。
 *
 * <p><b>性能说明（2026-09-14 实测，44 万行）</b>：
 * <ul>
 *   <li>{@code device_telemetry} 按月分区（p202608…p202707 + pmax）→ 带 {@code ts} 条件的查询
 *       可走<b>分区裁剪</b>，只需扫 1 个分区；</li>
 *   <li>无 {@code ts} 条件的 {@link #countAllTelemetry()} 是全表扫描（唯一的重查询），
 *       实测净耗时约 <b>0.25s</b>；</li>
 *   <li>该值同时作为 D5 报告「查询 RT」组的 MySQL 侧基线，迁移 TDengine 后用同样查询对比。</li>
 * </ul>
 */
@Mapper
public interface StatsMapper {

    /** 消息总量（全表扫描，唯一的重查询） */
    @Select("SELECT COUNT(*) FROM device_telemetry")
    long countAllTelemetry();

    /** 指定时间起点的消息量（走分区裁剪） */
    @Select("SELECT COUNT(*) FROM device_telemetry WHERE ts >= #{start}")
    long countTelemetrySince(@Param("start") String start);

    /** 按天聚合（bucket = yyyy-MM-dd，升序） */
    @Select("SELECT DATE_FORMAT(ts, '%Y-%m-%d') AS bucket, COUNT(*) AS cnt " +
            "FROM device_telemetry WHERE ts >= #{start} " +
            "GROUP BY bucket ORDER BY bucket")
    List<Map<String, Object>> countTelemetryByDay(@Param("start") String start);

    /** 按月聚合（bucket = yyyy-MM，升序） */
    @Select("SELECT DATE_FORMAT(ts, '%Y-%m') AS bucket, COUNT(*) AS cnt " +
            "FROM device_telemetry WHERE ts >= #{start} " +
            "GROUP BY bucket ORDER BY bucket")
    List<Map<String, Object>> countTelemetryByMonth(@Param("start") String start);

    // ===== 小表计数（product / device 合计不足 1100 行，直查无压力）=====

    /** 产品总数（未删除） */
    @Select("SELECT COUNT(*) FROM product WHERE deleted = 0")
    long countProducts();

    /** 已启用产品数 */
    @Select("SELECT COUNT(*) FROM product WHERE deleted = 0 AND status = 1")
    long countProductsEnabled();

    /** 设备总数（未删除） */
    @Select("SELECT COUNT(*) FROM device WHERE deleted = 0")
    long countDevices();

    /** 在线设备数 */
    @Select("SELECT COUNT(*) FROM device WHERE deleted = 0 AND online_status = 1")
    long countDevicesOnline();

    /** 已启用设备数（仅看设备**自身** status，不含产品级联 —— 保留原义） */
    @Select("SELECT COUNT(*) FROM device WHERE deleted = 0 AND status = 1")
    long countDevicesEnabled();

    // ===== 可用性口径（2026-09-15 新增）=====
    //
    // 「设备能不能用」= 设备自身启用 **AND** 所属产品启用，与 AuthServiceImpl 的判定同构
    // （authenticate / isDeviceEnabled 走的就是 deviceEnabled && isProductEnabled）。
    // 只看 device.status 会把「产品被停用 → 整条产品线设备全废」这半边完全漏掉：
    // 那批设备自身 status 仍是 1，既不计入 disabled，也不代表能用。

    /** 可用设备数（设备自身启用 **且** 所属产品启用） */
    @Select("SELECT COUNT(*) FROM device d JOIN product p ON d.product_key = p.product_key " +
            "WHERE d.deleted = 0 AND d.status = 1 AND p.deleted = 0 AND p.status = 1")
    long countDevicesAvailable();

    /** 被产品牵连的设备数（设备自身**是**启用的，但所属产品已停用） */
    @Select("SELECT COUNT(*) FROM device d JOIN product p ON d.product_key = p.product_key " +
            "WHERE d.deleted = 0 AND d.status = 1 AND p.deleted = 0 AND p.status = 0")
    long countDevicesBlockedByProduct();
}
