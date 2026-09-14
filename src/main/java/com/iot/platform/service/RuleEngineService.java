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
}
