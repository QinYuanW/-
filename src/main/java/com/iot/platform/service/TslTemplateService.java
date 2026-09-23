package com.iot.platform.service;

import com.iot.platform.dto.TslTemplateRequest;
import com.iot.platform.dto.TslTemplateVO;

import java.util.List;

/**
 * 通用物模型模板（用户自定义）。
 *
 * <p>内置模板不在这里 —— 它们在控制台前端常量里（随代码版本走）。
 * 本服务只负责"用户自己存下来的模板"。
 */
public interface TslTemplateService {

    /**
     * 模板列表。
     *
     * @param category 分类过滤（可空）
     * @param keyword  名称/说明模糊匹配（可空）
     */
    List<TslTemplateVO> list(String category, String keyword);

    TslTemplateVO getById(Long id);

    /** @return 新建的模板 id */
    Long create(TslTemplateRequest request);

    void update(Long id, TslTemplateRequest request);

    /** 逻辑删除（保留行，便于排查"模板去哪了"） */
    void delete(Long id);
}
