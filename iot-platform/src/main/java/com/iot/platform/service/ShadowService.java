package com.iot.platform.service;

import java.util.Map;

/**
 * E1 设备影子：云端保存设备的「期望值 desired」与「上报值 reported」，并计算二者差异 delta。
 *
 * <p>存储：Redis Hash {@code shadow:{deviceKey}}，field 前缀区分 {@code desired:} / {@code reported:}。
 * 单 key 设计使一次 {@code HGETALL} 即可取全量并本地算 delta，无需跨 key 往返。
 *
 * <p>注意：影子是<b>增强能力</b>——调用方（遥测写路径）必须自行隔离异常，
 * 绝不能让影子故障把遥测打进 retry/DLT（见 E1 设计 §DP-4）。
 */
public interface ShadowService {

    /** 读取影子全量：{@code {desired:{}, reported:{}, delta:{}}} */
    Map<String, Object> getShadow(String deviceKey);

    /**
     * 写入期望值。
     *
     * @return 实际被接受的属性（未在物模型中声明、或声明为 readonly 的属性会被拒绝）
     */
    Map<String, Object> setDesired(String deviceKey, Map<String, Object> desired);

    /** 更新上报值（由遥测写路径旁路调用；只接受物模型中声明的属性） */
    void updateReported(String deviceKey, Map<String, Object> reported);

    /** 仅取 delta（desired 与 reported 不一致的属性） */
    Map<String, Object> getDelta(String deviceKey);

    /** 清除某设备的影子（设备删除/重置时使用） */
    void clear(String deviceKey);

    /**
     * E5：物模型（TSL）变更后，失效指定设备的「TSL 属性白名单」缓存。
     *
     * <p>不清缓存的话，新增的属性会在最长 1 小时内被影子当作「未声明属性」拒绝/漏记。
     */
    void invalidateTslCache(java.util.Collection<String> deviceKeys);
}
