package com.iot.platform.service;

import java.util.Map;

/**
 * E6 控制台统计服务（仪表盘「物联概览」数据源）。
 */
public interface StatsService {

    /** 概览统计：product / device / message / alarm 四组数字 */
    Map<String, Object> overview();

    /**
     * 消息趋势。
     *
     * @param granularity 粒度：{@code day}（默认）或 {@code month}
     * @param range       day 时表示天数（默认 30）、month 时表示月数（默认 12）
     * @return {@code {granularity, items:[{bucket, message, alarm}]}}
     */
    Map<String, Object> messageTrend(String granularity, Integer range);

    /**
     * 告警列表（E6 阶段为<b>空壳</b>：告警数据链路依赖 E3，此处只保证接口通、结构定型）。
     */
    Map<String, Object> alerts(Integer page, Integer size);
}
