package com.iot.platform.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iot.platform.common.Result;
import com.iot.platform.dto.DeviceRuleRequest;
import com.iot.platform.dto.DeviceRuleVO;
import com.iot.platform.service.DeviceRuleService;
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
import java.util.Map;

/**
 * 告警规则管理接口（E3 尾巴，契约 §11）。
 *
 * <p>补齐 E3 留下的断头路：规则引擎（评估）早已落地，但规则的增删改查一直只能进 MySQL 敲 SQL
 * —— 有统计、无配置入口。本控制器把 {@code device_rule} 的配置面开放给控制台。
 *
 * <p>启停单独走 {@code PUT /rules/{id}/status}，与产品/设备的启停端点风格一致
 * （语义单一：只改 enabled，不做全量覆盖）。
 *
 * <p>路径 {@code /api/v1/rules} 在 {@code WebMvcConfig} 的保护范围内（JWT 必需）。
 */
@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
public class DeviceRuleController {

    private final DeviceRuleService ruleService;

    /** 规则列表（可按产品 / 名称关键字 / 启停状态过滤） */
    @GetMapping
    public Result<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size,
                                           @RequestParam(name = "product_key", required = false) String productKey,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) Integer enabled) {
        Page<DeviceRuleVO> result = ruleService.listRules(page, size, productKey, keyword, enabled);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", result.getTotal());
        data.put("items", result.getRecords());
        return Result.ok(data);
    }

    /** 规则详情 */
    @GetMapping("/{id}")
    public Result<DeviceRuleVO> detail(@PathVariable Long id) {
        return Result.ok(ruleService.getById(id));
    }

    /** 新建规则（返回自增 id，供前端跳转/提示） */
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody DeviceRuleRequest request) {
        Long id = ruleService.create(request);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        return Result.ok(data);
    }

    /** 编辑规则（全字段覆盖） */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody DeviceRuleRequest request) {
        ruleService.update(id, request);
        return Result.ok();
    }

    /** 启用 / 停用：body {@code {"enabled": 0|1}} */
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        ruleService.updateStatus(id, body.get("enabled"));
        return Result.ok();
    }

    /** 删除规则（逻辑删除，历史告警保留） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        ruleService.delete(id);
        return Result.ok();
    }
}
