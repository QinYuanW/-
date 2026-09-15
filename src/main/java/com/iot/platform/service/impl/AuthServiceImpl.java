package com.iot.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.iot.platform.entity.Device;
import com.iot.platform.entity.Product;
import com.iot.platform.mapper.DeviceMapper;
import com.iot.platform.mapper.ProductMapper;
import com.iot.platform.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 设备接入认证（EMQX 回调）。
 *
 * <h3>为什么认证要管"启用状态"</h3>
 * 认证是整个平台唯一的接入闸门 —— 只有在这里拦，"停用"才真正生效。
 * 仅靠遥测入口拒绝是不够的（设备照样建立 MQTT 会话、照样占连接数）。
 *
 * <ul>
 *   <li>设备 {@code status = 0} → 拒绝</li>
 *   <li>所属产品 {@code status = 0} → 拒绝（级联）</li>
 * </ul>
 *
 * <h3>级联判断为什么放这里，而不是停用产品时批量改设备</h3>
 * 若产品停用就批量把子设备 {@code status} 改成 0，**产品恢复时无法区分**
 * 「本来就是停用的设备」与「因产品停用而受影响的设备」——信息不可逆丢失。
 * 因此只改产品自己，有效状态在此处实时合成：{@code device.status && product.status}。
 *
 * <h3>缓存格式（v3）</h3>
 * 值 = {@code <secret>|<deviceEnabled>|<product_key>}。
 * <p><b>注意这里存的是 product_key，而不是「产品是否启用」。</b>
 * v2 曾把 {@code productEnabled} 位也塞进设备缓存，结果是：产品停用时只清了
 * {@code prod:enabled:} 却清不掉设备缓存，认证回调读到的仍是旧的
 * {@code productEnabled=1}，<b>停用产品最长 300s 内依然放行</b>
 * （2026-09-15 实测复现：停用产品后重新认证返回 204）。
 * 现在改为<b>每次认证独立查产品状态</b>（{@link #isProductEnabled} 走
 * {@code prod:enabled:} 缓存，一次 Redis GET，约 0.2ms），
 * 这样「停用产品」只需清理那一个 key 即可全量即时生效，无需按产品扇出清理 N 台设备。
 * <p>key 前缀带 {@code v3} 是为了让旧格式缓存自然失效，无需手工清理 Redis。
 * <p><b>停用状态同样要缓存</b> —— 否则被停用的设备每次重连都穿透打到 DB（停用设备往往还会反复重试）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    /** 带版本号：缓存格式变更时换前缀即可，旧数据自然过期（v2 → v3：产品状态移出设备缓存） */
    private static final String CACHE_PREFIX = "auth:v3:";
    /** 产品启用状态（认证是热路径，避免每次都回查 product 表） */
    private static final String PRODUCT_CACHE_PREFIX = "prod:enabled:";
    private static final long TTL_SEC = 300L;

    private final DeviceMapper deviceMapper;
    private final ProductMapper productMapper;
    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean authenticate(String deviceKey, String password) {
        // 1. 命中缓存
        String cached = redisTemplate.opsForValue().get(CACHE_PREFIX + deviceKey);
        if (cached != null) {
            String[] parts = cached.split("\\|", 3);
            if (parts.length == 3) {
                // 产品状态**每次独立查**（走 prod:enabled: 缓存），不再从设备缓存里取 ——
                // 否则「停用产品」必须扇出清掉该产品下全部设备缓存才生效（v2 的漏洞）
                boolean enabled = "1".equals(parts[1]) && isProductEnabled(parts[2]);
                return enabled && parts[0].equals(password);
            }
            log.warn("认证缓存格式异常，拒绝接入: device_key={}", deviceKey);
            return false;
        }

        // 2. 查设备
        Device device = deviceMapper.selectOne(
                new LambdaQueryWrapper<Device>().eq(Device::getDeviceKey, deviceKey));
        if (device == null) {
            return false;
        }

        boolean deviceEnabled = device.getStatus() != null && device.getStatus() == 1;

        // 3. 回填缓存（停用状态也缓存）—— 存 product_key，不存产品启用状态
        try {
            redisTemplate.opsForValue().set(
                    CACHE_PREFIX + deviceKey,
                    device.getDeviceSecret() + "|" + (deviceEnabled ? 1 : 0) + "|" + device.getProductKey(),
                    Duration.ofSeconds(TTL_SEC));
        } catch (Exception e) {
            log.debug("认证缓存写入失败: {}", e.getMessage());
        }

        return deviceEnabled && isProductEnabled(device.getProductKey())
                && device.getDeviceSecret().equals(password);
    }

    /** 产品启用状态（Redis 缓存 5 分钟）。产品不存在时按"启用"处理，交由上层判空。 */
    @Override
    public boolean isProductEnabled(String productKey) {
        if (productKey == null) return true;

        String cacheKey = PRODUCT_CACHE_PREFIX + productKey;
        try {
            String c = redisTemplate.opsForValue().get(cacheKey);
            if (c != null) return "1".equals(c);
        } catch (Exception e) {
            log.debug("产品状态缓存读取失败，回退查库: {}", e.getMessage());
        }

        Product p = productMapper.selectOne(
                new LambdaQueryWrapper<Product>().eq(Product::getProductKey, productKey));
        boolean enabled = p == null || (p.getStatus() != null && p.getStatus() == 1);
        try {
            redisTemplate.opsForValue().set(cacheKey, enabled ? "1" : "0", Duration.ofSeconds(TTL_SEC));
        } catch (Exception e) {
            log.debug("产品状态缓存写入失败: {}", e.getMessage());
        }
        return enabled;
    }

    @Override
    public boolean isDeviceEnabled(String deviceKey) {
        // 复用认证缓存：value = secret|deviceEnabled|product_key
        String cached = redisTemplate.opsForValue().get(CACHE_PREFIX + deviceKey);
        if (cached != null) {
            String[] parts = cached.split("\\|", 3);
            if (parts.length == 3) {
                // 与 authenticate 同源：产品状态独立查，保证「停用产品」在接入侧与上报侧同步生效
                return "1".equals(parts[1]) && isProductEnabled(parts[2]);
            }
        }

        // 缓存未命中：查库判一次（不主动回填 —— 遥测高频，回填交给认证流程做更合适）
        Device device = deviceMapper.selectOne(
                new LambdaQueryWrapper<Device>().eq(Device::getDeviceKey, deviceKey));
        if (device == null) return false;

        boolean deviceEnabled = device.getStatus() != null && device.getStatus() == 1;
        return deviceEnabled && isProductEnabled(device.getProductKey());
    }

    @Override
    public void evictAuthCache(String deviceKey) {
        try {
            redisTemplate.delete(CACHE_PREFIX + deviceKey);
        } catch (Exception e) {
            log.debug("清除认证缓存失败: {}", e.getMessage());
        }
    }

    @Override
    public void evictProductCache(String productKey) {
        try {
            redisTemplate.delete(PRODUCT_CACHE_PREFIX + productKey);
        } catch (Exception e) {
            log.debug("清除产品状态缓存失败: {}", e.getMessage());
        }
    }
}
