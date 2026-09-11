package com.iot.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iot.platform.entity.DeviceTelemetry;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface DeviceTelemetryMapper extends BaseMapper<DeviceTelemetry> {

    @Select("SELECT t.property_key, t.value FROM device_telemetry t " +
            "INNER JOIN (SELECT property_key, MAX(ts) AS max_ts FROM device_telemetry " +
            "WHERE device_id = #{deviceId} GROUP BY property_key) latest " +
            "ON t.device_id = #{deviceId} AND t.property_key = latest.property_key AND t.ts = latest.max_ts")
    List<Map<String, String>> selectLatestByDevice(Long deviceId);

    /**
     * 批量幂等插入：一条 multi-values INSERT 代替逐条，显著降低写路径往返次数（BUG-17 §3.2）。
     * D2 幂等去重：同一设备同一属性同一值同一 ts 视为同一条上报（uk_device_prop_val_ts），
     * 冲突更新 id=id（无实际变更）吞掉 offset 重放 / webhook 重发的重复，不落脏数据、不中断批量。
     * 注：唯一键必须含 value（BUG-20）——仅 (device_id,property_key,ts) 时 QoS0 突发共享同毫秒 ts，
     *    会误折叠不同上报并静默丢失；入 value 后并发上报 value 不同不误删，真重放 value+ts 全同仍去重。
     * 调用方保证 list 内 ts 相同（同一设备的同一上报报文），避免跨分区。
     */
    @Insert("<script>INSERT INTO device_telemetry (device_id, property_key, value, value_type, ts) VALUES " +
            "<foreach collection='list' item='t' separator=','>" +
            "(#{t.deviceId}, #{t.propertyKey}, #{t.value}, #{t.valueType}, #{t.ts})" +
            "</foreach> ON DUPLICATE KEY UPDATE id = id</script>")
    int insertBatch(@Param("list") List<DeviceTelemetry> list);
}