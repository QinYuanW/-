package com.iot.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iot.platform.entity.DeviceRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * E3 规则定义。
 *
 * <p>CRUD 由 {@code BaseMapper} 提供，条件查询由上层 {@code LambdaQueryWrapper} 组装，
 * 故此处无需自定义 SQL。
 */
@Mapper
public interface DeviceRuleMapper extends BaseMapper<DeviceRule> {
}
