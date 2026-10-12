package com.iot.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iot.platform.entity.TslTemplate;
import org.apache.ibatis.annotations.Mapper;

/**
 * 通用物模型模板 Mapper（用户自定义）。
 * 纯 BaseMapper 即可：筛选条件简单，不需要自定义 SQL。
 */
@Mapper
public interface TslTemplateMapper extends BaseMapper<TslTemplate> {
}
