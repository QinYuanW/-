package com.iot.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.entity.Device;
import com.iot.platform.entity.Product;
import com.iot.platform.mapper.DeviceMapper;
import com.iot.platform.mapper.ProductMapper;
import com.iot.platform.service.ShadowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

/**
 * E1 设备影子实现。
 *
 * <p>Redis 结构（单 Hash，field 前缀区分）：
 * <pre>
 * shadow:{deviceKey}
 *   desired:temperature  = 26
 *   reported:temperature = 24
 * </pre>
 *
 * <p>属性白名单：从 {@code product.tsl} 解析并在 Redis 缓存（TTL 1h），避免每条遥测都查库。
 * 查不到 TSL 时<b>降级为放行全部属性</b>（不因物模型缺失而丢数据）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShadowServiceImpl implements ShadowService {

    private final StringRedisTemplate redisTemplate;
    private final DeviceMapper deviceMapper;
    private final ProductMapper productMapper;
    private final ObjectMapper objectMapper;

    private static final String KEY_PREFIX = "shadow:";
    private static final String TSL_CACHE_PREFIX = "shadow:tsl:";
    private static final String D = "desired:";
    private static final String R = "reported:";
    /** 空属性集的缓存哨兵：命中即代表「TSL 无属性」，避免每次都回查 DB */
    private static final String NONE = "__NONE__";
    private static final Duration TSL_TTL = Duration.ofHours(1);

    @Override
    public Map<String, Object> getShadow(String deviceKey) {
        Map<String, String> desiredEnc = new LinkedHashMap<>();
        Map<String, String> reportedEnc = new LinkedHashMap<>();

        Map<Object, Object> raw;
        try {
            raw = redisTemplate.opsForHash().entries(key(deviceKey));
        } catch (Exception e) {
            log.warn("shadow read failed: device_key={}, err={}", deviceKey, e.getMessage());
            raw = Collections.emptyMap();
        }

        for (Map.Entry<Object, Object> e : raw.entrySet()) {
            String field = String.valueOf(e.getKey());
            String value = String.valueOf(e.getValue());
            if (field.startsWith(D)) {
                desiredEnc.put(field.substring(D.length()), value);
            } else if (field.startsWith(R)) {
                reportedEnc.put(field.substring(R.length()), value);
            }
        }

        // delta = desired 中与 reported 编码值不一致（或 reported 缺失）的属性
        Map<String, Object> desired = new LinkedHashMap<>();
        Map<String, Object> reported = new LinkedHashMap<>();
        Map<String, Object> delta = new LinkedHashMap<>();
        desiredEnc.forEach((k, v) -> desired.put(k, decode(v)));
        reportedEnc.forEach((k, v) -> reported.put(k, decode(v)));
        desiredEnc.forEach((k, v) -> {
            if (!v.equals(reportedEnc.get(k))) {
                delta.put(k, decode(v));
            }
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("desired", desired);
        out.put("reported", reported);
        out.put("delta", delta);
        return out;
    }

    @Override
    public Map<String, Object> setDesired(String deviceKey, Map<String, Object> desired) {
        if (desired == null || desired.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<String> writable = tslProperties(deviceKey, true);
        Map<String, String> toWrite = new LinkedHashMap<>();
        Map<String, Object> accepted = new LinkedHashMap<>();

        for (Map.Entry<String, Object> e : desired.entrySet()) {
            String prop = e.getKey();
            if (!writable.isEmpty() && !writable.contains(prop)) {
                log.warn("shadow desired rejected (未在物模型中声明为可写): device_key={}, property={}",
                        deviceKey, prop);
                continue;
            }
            toWrite.put(D + prop, encode(e.getValue()));
            accepted.put(prop, e.getValue());
        }
        if (!toWrite.isEmpty()) {
            redisTemplate.opsForHash().putAll(key(deviceKey), toWrite);
        }
        return accepted;
    }

    @Override
    public void updateReported(String deviceKey, Map<String, Object> reported) {
        if (reported == null || reported.isEmpty()) {
            return;
        }
        Set<String> declared = tslProperties(deviceKey, false);
        Map<String, String> toWrite = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : reported.entrySet()) {
            // 只记录物模型里声明过的属性，避免设备上报的临时 key 污染影子
            if (!declared.isEmpty() && !declared.contains(e.getKey())) {
                continue;
            }
            toWrite.put(R + e.getKey(), encode(e.getValue()));
        }
        if (!toWrite.isEmpty()) {
            redisTemplate.opsForHash().putAll(key(deviceKey), toWrite);
        }
    }

    @Override
    public Map<String, Object> getDelta(String deviceKey) {
        Object delta = getShadow(deviceKey).get("delta");
        if (delta instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Collections.emptyMap();
    }

    @Override
    public void clear(String deviceKey) {
        try {
            redisTemplate.delete(key(deviceKey));
            redisTemplate.delete(TSL_CACHE_PREFIX + deviceKey + ":all");
            redisTemplate.delete(TSL_CACHE_PREFIX + deviceKey + ":writable");
        } catch (Exception e) {
            log.warn("shadow clear failed: device_key={}, err={}", deviceKey, e.getMessage());
        }
    }

    @Override
    public void invalidateTslCache(java.util.Collection<String> deviceKeys) {
        if (deviceKeys == null || deviceKeys.isEmpty()) {
            return;
        }
        int cleared = 0;
        for (String dk : deviceKeys) {
            try {
                cleared += redisTemplate.delete(TSL_CACHE_PREFIX + dk + ":all") ? 1 : 0;
                cleared += redisTemplate.delete(TSL_CACHE_PREFIX + dk + ":writable") ? 1 : 0;
            } catch (Exception e) {
                log.warn("shadow TSL cache invalidate failed: device_key={}, err={}", dk, e.getMessage());
            }
        }
        log.info("shadow TSL cache invalidated: devices={}, keys_deleted={}", deviceKeys.size(), cleared);
    }

    // ------------------------------------------------------------------ 内部

    private String key(String deviceKey) {
        return KEY_PREFIX + deviceKey;
    }

    /**
     * 编码为 Redis 存储值（JSON 字面量），保证 set → get → 下发全链路<b>类型无损</b>：
     * <ul>
     *   <li>数值 → BigDecimal 规范形式（{@code 26.0 → 26}），避免 desired=26 与 reported=26.0 被误判为不一致</li>
     *   <li>布尔 → {@code true} / {@code false}</li>
     *   <li>字符串 → JSON 字符串字面量（带引号），使值为 {@code "true"} / {@code "26"} 的字符串属性也能无损还原</li>
     * </ul>
     */
    private String encode(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number n) {
            try {
                return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException e) {
                return String.valueOf(value);
            }
        }
        if (value instanceof Boolean b) {
            return b.toString();
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /** 解码为原生类型（供 API 返回与下发使用）；无法解析时原样返回 */
    private Object decode(String stored) {
        if (stored == null || stored.isEmpty()) {
            return "";
        }
        try {
            return objectMapper.readValue(stored, Object.class);
        } catch (Exception e) {
            return stored;
        }
    }

    /**
     * 取物模型属性名集合（带 Redis 缓存）。
     *
     * @param writableOnly true 只取 {@code access != readonly}
     * @return 空集表示「无法确定」→ 调用方应降级为放行
     */
    private Set<String> tslProperties(String deviceKey, boolean writableOnly) {
        String cacheKey = TSL_CACHE_PREFIX + deviceKey + (writableOnly ? ":writable" : ":all");
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return NONE.equals(cached) ? Collections.emptySet() : split(cached);
            }
        } catch (Exception e) {
            log.warn("shadow TSL cache read failed: {}", e.getMessage());
        }

        Set<String> props = new LinkedHashSet<>();
        boolean parsed = false;
        try {
            Device device = deviceMapper.selectOne(
                    new LambdaQueryWrapper<Device>().eq(Device::getDeviceKey, deviceKey));
            if (device != null) {
                Product product = productMapper.selectOne(
                        new LambdaQueryWrapper<Product>().eq(Product::getProductKey, device.getProductKey()));
                if (product != null && product.getTsl() != null && !product.getTsl().isBlank()) {
                    parsed = true;
                    Map<String, Object> tsl = objectMapper.readValue(
                            product.getTsl(), new TypeReference<Map<String, Object>>() {});
                    Object rawProps = tsl.get("properties");
                    if (rawProps instanceof List<?> list) {
                        for (Object o : list) {
                            if (!(o instanceof Map<?, ?> m) || m.get("key") == null) {
                                continue;
                            }
                            if (writableOnly && "readonly".equals(String.valueOf(m.get("access")))) {
                                continue;
                            }
                            props.add(String.valueOf(m.get("key")));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("shadow TSL parse failed: device_key={}, err={}", deviceKey, e.getMessage());
            return Collections.emptySet();
        }

        if (parsed) {
            try {
                redisTemplate.opsForValue()
                        .set(cacheKey, props.isEmpty() ? NONE : String.join(",", props), TSL_TTL);
            } catch (Exception e) {
                log.warn("shadow TSL cache write failed: {}", e.getMessage());
            }
        }
        return props;
    }

    private Set<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String s : csv.split(",")) {
            if (!s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }
}
