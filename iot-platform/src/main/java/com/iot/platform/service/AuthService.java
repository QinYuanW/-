package com.iot.platform.service;

/** 设备接入认证（EMQX 回调）。 */
public interface AuthService {

    /**
     * 校验设备三元组密钥 + 启用状态。
     *
     * @return true 才允许接入；设备或所属产品被停用时一律 false
     */
    boolean authenticate(String deviceKey, String password);

    /** 清除某设备的认证缓存（状态变更后调用，让"停用"立即生效，不必等 TTL） */
    void evictAuthCache(String deviceKey);

    /** 清除某产品的启用状态缓存 */
    void evictProductCache(String productKey);

    /**
     * 产品是否处于启用状态（Redis 缓存）。
     *
     * <p>供遥测 / 指令入口复用：认证只能拦住**新连接**，停用前已建立的长连接要靠入口校验兜住，
     * 否则「产品停用」对存量连接形同虚设。
     */
    boolean isProductEnabled(String productKey);

    /**
     * 设备是否处于「可工作」状态 = 设备自身启用 **且** 所属产品启用。
     *
     * <p>供遥测 / 指令入口做兜底校验：认证只能拦住**新连接**，停用前已建立的长连接
     * 必须靠入口校验拦截，否则「停用」对存量连接形同虚设。
     *
     * <p>实现复用认证缓存（命中时仅一次 Redis GET，不查库）。
     */
    boolean isDeviceEnabled(String deviceKey);
}
