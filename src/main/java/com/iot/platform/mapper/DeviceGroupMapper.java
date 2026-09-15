package com.iot.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iot.platform.entity.DeviceGroup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** E4 设备分组。 */
@Mapper
public interface DeviceGroupMapper extends BaseMapper<DeviceGroup> {

    /**
     * 分组列表 + 各分组设备数。
     *
     * <p>用 LEFT JOIN 一次查出，避免「先查分组、再逐个 count」的 N+1；
     * 设备侧过滤 {@code d.deleted = 0}（软删除设备不计入）。
     */
    @Select("SELECT g.id, g.name, g.description, g.sort_order AS sortOrder, g.created_at AS createdAt, " +
            "       COUNT(d.id) AS deviceCount " +
            "FROM device_group g " +
            "LEFT JOIN device d ON d.group_id = g.id AND d.deleted = 0 " +
            "WHERE g.deleted = 0 " +
            "GROUP BY g.id, g.name, g.description, g.sort_order, g.created_at " +
            "ORDER BY g.sort_order, g.id")
    List<Map<String, Object>> selectWithDeviceCount();

    /** 未分组设备数（列表页「未分组」筛选用） */
    @Select("SELECT COUNT(*) FROM device WHERE deleted = 0 AND (group_id IS NULL OR group_id = 0)")
    long countUngrouped();
}
