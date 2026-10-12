package com.iot.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iot.platform.entity.Device;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DeviceMapper extends BaseMapper<Device> {
}