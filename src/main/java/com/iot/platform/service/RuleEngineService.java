package com.iot.platform.service;

import com.iot.platform.dto.TelemetryRequest;

/**
 * E3 规则引擎：对已落库的遥测做阈值评估，命中则生成告警（可选联动下发指令）。
 */
public interface RuleEngineService {

    /**
     * 评估一条遥测。
     *
     * <p>由遥测消费者在<b>落库成功之后</b>调用。
     * 实现内部必须吞掉所有异常 —— 规则评估失败绝不能影响"遥测入库"这条主链路。
     */
    void evaluate(TelemetryRequest request);

    /**
     * 清某个产品的规则列表缓存（{@code rules:{productKey}}）。
     *
     * <p><b>规则增删改后必须调用</b>——评估挂在每条上报上，为省一次 SELECT 缓存了 60s，
     * 不清就会出现「界面上明明改了、上报却还是老规则」的假故障。
     * 改规则时若改了 {@code product_key}，**新旧两个产品都要清**。
     */
    void evictRuleCache(String productKey);

    /**
     * 清某条规则的冷却窗口（{@code rule:cd:{ruleId}:*}）。
     *
     * <p>冷却键 TTL = {@code cooldownSec}，是「同规则同设备 N 秒内只告警一次」的抑制位。
     * 改完阈值/启停后若不清，演示时会遇到「刚改完立刻上报却没告警」——
     * 因为它仍落在上一次告警的冷却窗口里，极易被误判成"改动没生效"。
     */
    void evictCooldown(Long ruleId);
}
