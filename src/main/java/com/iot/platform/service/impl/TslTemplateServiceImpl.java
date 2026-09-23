package com.iot.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.common.BizException;
import com.iot.platform.common.ErrorCode;
import com.iot.platform.dto.TslTemplateRequest;
import com.iot.platform.dto.TslTemplateVO;
import com.iot.platform.entity.TslTemplate;
import com.iot.platform.mapper.TslTemplateMapper;
import com.iot.platform.service.TslTemplateService;
import com.iot.platform.util.TslValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用物模型模板服务。
 *
 * <p>与产品物模型共用 {@link TslValidator} —— 保证"模板存得进"与"套到产品能存"是同一套规则，
 * 否则会出现"模板能建、套用被拒"这种用户完全无从理解的组合。
 *
 * <p>tsl 的落库路径与 {@code product.tsl} 一致：Map → Jackson 序列化 → json 列，
 * 出参反序列化回 Map 给前端。避免二次编码（把 JSON 当字符串塞进 JSON）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TslTemplateServiceImpl implements TslTemplateService {

    private final TslTemplateMapper templateMapper;
    private final ObjectMapper objectMapper;

    @Override
    public List<TslTemplateVO> list(String category, String keyword) {
        LambdaQueryWrapper<TslTemplate> w = new LambdaQueryWrapper<TslTemplate>()
                .orderByDesc(TslTemplate::getUpdatedAt)
                .orderByDesc(TslTemplate::getId);
        if (category != null && !category.isBlank()) {
            w.eq(TslTemplate::getCategory, category.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            w.and(q -> q.like(TslTemplate::getName, kw).or().like(TslTemplate::getDescription, kw));
        }
        List<TslTemplateVO> out = new ArrayList<>();
        for (TslTemplate t : templateMapper.selectList(w)) {
            out.add(toVo(t));
        }
        return out;
    }

    @Override
    public TslTemplateVO getById(Long id) {
        TslTemplate t = templateMapper.selectById(id);
        if (t == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "模板不存在: " + id);
        }
        return toVo(t);
    }

    @Override
    public Long create(TslTemplateRequest request) {
        validateRequest(request, true);
        TslTemplate t = new TslTemplate();
        applyRequest(t, request);
        t.setBuiltin(0);
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        templateMapper.insert(t);
        log.info("tsl template created: id={} name={}", t.getId(), t.getName());
        return t.getId();
    }

    @Override
    public void update(Long id, TslTemplateRequest request) {
        TslTemplate exist = templateMapper.selectById(id);
        if (exist == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "模板不存在: " + id);
        }
        // tsl 允许不改（只改名/分类/说明）；一旦给了就必须过校验
        validateRequest(request, false);
        applyRequest(exist, request);
        exist.setUpdatedAt(LocalDateTime.now());
        templateMapper.updateById(exist);
        log.info("tsl template updated: id={} name={}", id, exist.getName());
    }

    @Override
    public void delete(Long id) {
        if (templateMapper.selectById(id) == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "模板不存在: " + id);
        }
        // 逻辑删除（@TableLogic 自动改写为 update deleted=1）
        templateMapper.deleteById(id);
        log.info("tsl template deleted: id={}", id);
    }

    /** @param tslRequired 创建时物模型必填；更新时允许只改名字/说明 */
    private void validateRequest(TslTemplateRequest request, boolean tslRequired) {
        if (request == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "请求体不能为空");
        }
        if (request.getName() == null || request.getName().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "模板名称不能为空");
        }
        if (request.getName().trim().length() > 64) {
            throw new BizException(ErrorCode.PARAM_ERROR, "模板名称不能超过 64 字");
        }
        if (request.getDescription() != null && request.getDescription().length() > 255) {
            throw new BizException(ErrorCode.PARAM_ERROR, "说明不能超过 255 字");
        }
        if (tslRequired && request.getTsl() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "物模型（tsl）不能为空");
        }
        if (request.getTsl() != null) {
            TslValidator.validate(request.getTsl());
        }
    }

    private void applyRequest(TslTemplate t, TslTemplateRequest request) {
        t.setName(request.getName().trim());
        t.setCategory(request.getCategory() == null ? null : blankToNull(request.getCategory()));
        t.setDescription(request.getDescription() == null ? null : blankToNull(request.getDescription()));
        if (request.getTsl() != null) {
            t.setTsl(toJson(request.getTsl()));
        }
    }

    private String blankToNull(String s) {
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }

    private TslTemplateVO toVo(TslTemplate t) {
        TslTemplateVO vo = new TslTemplateVO();
        vo.setId(t.getId());
        vo.setName(t.getName());
        vo.setCategory(t.getCategory());
        vo.setDescription(t.getDescription());
        vo.setBuiltin(t.getBuiltin());
        vo.setCreatedAt(t.getCreatedAt());
        vo.setUpdatedAt(t.getUpdatedAt());
        vo.setTsl(parseTsl(t.getTsl()));
        return vo;
    }

    private Map<String, Object> parseTsl(String json) {
        if (json == null || json.isBlank()) {
            return emptyTsl();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            // 库里存了坏 JSON 不该让整个列表接口挂掉：退化成空物模型并告警
            log.warn("template tsl parse failed, fallback to empty: {}", e.getMessage());
            return emptyTsl();
        }
    }

    private Map<String, Object> emptyTsl() {
        Map<String, Object> tsl = new LinkedHashMap<>();
        tsl.put("properties", new ArrayList<>());
        tsl.put("services", new ArrayList<>());
        tsl.put("events", new ArrayList<>());
        return tsl;
    }

    private String toJson(Map<String, Object> tsl) {
        try {
            return objectMapper.writeValueAsString(tsl);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "tsl 序列化失败");
        }
    }
}
