package com.iot.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iot.platform.entity.DeviceAlarm;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * E3 告警查询。
 *
 * <p>列表需要带出 {@code device_key} / {@code product_key} 供前端跳转与展示，
 * 故用自定义 SQL 做一次 JOIN，而不是让上层逐条回查设备表。
 */
@Mapper
public interface DeviceAlarmMapper extends BaseMapper<DeviceAlarm> {

    /** 告警分页（JOIN device 带出 device_key / product_key） */
    @Select("<script>" +
            "SELECT a.id, a.alarm_type AS alarmType, a.level, a.rule_name AS ruleName, " +
            "       a.content, a.status, a.created_at AS createdAt, " +
            "       d.device_key AS deviceKey, d.product_key AS productKey " +
            "FROM device_alarm a LEFT JOIN device d ON d.id = a.device_id " +
            "WHERE 1 = 1 " +
            "<if test='level != null'> AND a.level = #{level} </if>" +
            "<if test='status != null'> AND a.status = #{status} </if>" +
            "ORDER BY a.created_at DESC LIMIT #{offset}, #{size}" +
            "</script>")
    List<Map<String, Object>> selectPage(@Param("level") Integer level,
                                        @Param("status") Integer status,
                                        @Param("offset") int offset,
                                        @Param("size") int size);

    /** 同筛选条件下的总数（分页用） */
    @Select("<script>" +
            "SELECT COUNT(*) FROM device_alarm a WHERE 1 = 1 " +
            "<if test='level != null'> AND a.level = #{level} </if>" +
            "<if test='status != null'> AND a.status = #{status} </if>" +
            "</script>")
    long countByFilter(@Param("level") Integer level, @Param("status") Integer status);

    /** 告警类型分布（仪表盘「告警类型」面板） */
    @Select("<script>" +
            "SELECT alarm_type AS name, COUNT(*) AS count FROM device_alarm WHERE 1 = 1 " +
            "<if test='level != null'> AND level = #{level} </if>" +
            "<if test='status != null'> AND status = #{status} </if>" +
            "GROUP BY alarm_type ORDER BY count DESC" +
            "</script>")
    List<Map<String, Object>> countByType(@Param("level") Integer level, @Param("status") Integer status);

    /** 指定时间起点的告警数（overview 的 alarm 段） */
    @Select("SELECT COUNT(*) FROM device_alarm WHERE created_at >= #{start}")
    long countSince(@Param("start") String start);

    @Select("SELECT COUNT(*) FROM device_alarm")
    long countAll();
}
