package com.iot.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iot.platform.entity.DeviceTelemetry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

@Mapper
public interface DeviceTelemetryMapper extends BaseMapper<DeviceTelemetry> {

    @Select("SELECT t.property_key, t.value FROM device_telemetry t " +
            "INNER JOIN (SELECT property_key, MAX(ts) AS max_ts FROM device_telemetry " +
            "WHERE device_id = #{deviceId} GROUP BY property_key) latest " +
            "ON t.device_id = #{deviceId} AND t.property_key = latest.property_key AND t.ts = latest.max_ts")
    Map<String, String> selectLatestByDevice(Long deviceId);
}