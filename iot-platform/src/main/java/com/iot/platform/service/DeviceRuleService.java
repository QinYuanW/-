package com.iot.platform.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iot.platform.dto.DeviceRuleRequest;
import com.iot.platform.dto.DeviceRuleVO;

/**
 * E3 尾巴 · 告警规则管理（CRUD）。
 *
 * <p>与 {@link RuleEngineService} 分工：那个负责**评估**（运行期，热路径），
 * 这个负责**管理**（配置期，低频）。二者共享同一张 {@code device_rule} 表。
 *
 * <p><b>所有写操作都会清规则缓存与冷却窗口</b> —— 否则界面改完规则后，
 * 要么最长 60s 内不生效（规则列表缓存），要么被上一次告警的冷却窗口挡住，
 * 表现都是"改了没反应"。
 */
public interface DeviceRuleService {

    /** 规则分页（可按产品 / 名称关键字 / 启停状态过滤，含各规则累计告警数） */
    Page<DeviceRuleVO> listRules(int page, int size, String productKey, String keyword, Integer enabled);

    /** 规则详情（含累计告警数） */
    DeviceRuleVO getById(Long id);

    /** 新建规则，返回自增 id */
    Long create(DeviceRuleRequest request);

    /** 编辑规则（全字段覆盖，含把 action_payload / device_key 置空） */
    void update(Long id, DeviceRuleRequest request);

    /** 启用 / 停用 */
    void updateStatus(Long id, Integer enabled);

    /** 删除（逻辑删除；历史告警保留，其 rule_name 已冗余存档） */
    void delete(Long id);
}
