package com.iot.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iot.platform.entity.DeviceEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** E3 设备事件查询。 */
@Mapper
public interface DeviceEventMapper extends BaseMapper<DeviceEvent> {

    /** 事件分页（按事件时间倒序） */
    @Select("<script>" +
            "SELECT id, event_key AS eventKey, params, ts FROM device_event " +
            "WHERE device_id = #{deviceId} " +
            "<if test=\"eventKey != null and eventKey != ''\"> AND event_key = #{eventKey} </if>" +
            "ORDER BY ts DESC LIMIT #{offset}, #{size}" +
            "</script>")
    List<Map<String, Object>> selectPage(@Param("deviceId") Long deviceId,
                                        @Param("eventKey") String eventKey,
                                        @Param("offset") int offset,
                                        @Param("size") int size);

    @Select("<script>" +
            "SELECT COUNT(*) FROM device_event WHERE device_id = #{deviceId} " +
            "<if test=\"eventKey != null and eventKey != ''\"> AND event_key = #{eventKey} </if>" +
            "</script>")
    long countByFilter(@Param("deviceId") Long deviceId, @Param("eventKey") String eventKey);
}
