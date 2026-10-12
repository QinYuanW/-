package com.iot.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iot.platform.entity.Command;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CommandMapper extends BaseMapper<Command> {
}