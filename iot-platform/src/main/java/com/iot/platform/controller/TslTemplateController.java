package com.iot.platform.controller;

import com.iot.platform.common.Result;
import com.iot.platform.dto.TslTemplateRequest;
import com.iot.platform.dto.TslTemplateVO;
import com.iot.platform.service.TslTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用物模型模板接口（用户自定义）。
 *
 * <p>与内置模板的关系：内置模板在控制台前端常量里（随代码版本走，换库也在）；
 * 本接口只管用户自己存下来的模板。前端把两者合并展示。
 *
 * <p>路径 {@code /api/v1/tsl-templates} 在 {@code WebMvcConfig} 的保护范围内（JWT 必需）。
 */
@RestController
@RequestMapping("/api/v1/tsl-templates")
@RequiredArgsConstructor
public class TslTemplateController {

    private final TslTemplateService templateService;

    /** 模板列表（可按分类 / 关键字过滤） */
    @GetMapping
    public Result<Map<String, Object>> list(@RequestParam(required = false) String category,
                                           @RequestParam(required = false) String keyword) {
        List<TslTemplateVO> items = templateService.list(category, keyword);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", items.size());
        data.put("items", items);
        return Result.ok(data);
    }

    /** 模板详情 */
    @GetMapping("/{id}")
    public Result<TslTemplateVO> detail(@PathVariable Long id) {
        return Result.ok(templateService.getById(id));
    }

    /** 新建模板（body 需含 name 与 tsl） */
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody TslTemplateRequest request) {
        Long id = templateService.create(request);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        return Result.ok(data);
    }

    /** 更新模板（tsl 可缺省：只改名/分类/说明） */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody TslTemplateRequest request) {
        templateService.update(id, request);
        return Result.ok();
    }

    /** 删除模板（逻辑删除） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        templateService.delete(id);
        return Result.ok();
    }
}
