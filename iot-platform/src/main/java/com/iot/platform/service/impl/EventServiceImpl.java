package com.iot.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.dto.EventRequest;
import com.iot.platform.entity.Device;
import com.iot.platform.entity.DeviceEvent;
import com.iot.platform.entity.Product;
import com.iot.platform.mapper.DeviceEventMapper;
import com.iot.platform.mapper.DeviceMapper;
import com.iot.platform.mapper.ProductMapper;
import com.iot.platform.service.AuthService;
import com.iot.platform.service.EventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * E3 设备事件服务实现。
 *
 * <p>两个核心点：
 * <ol>
 *   <li><b>接收端吞异常</b>（契约 §8.1）：事件是"发生过"的事实，丢一条比拖垮 EMQX 入口更可接受
 *       —— 与遥测（进 Kafka 重试）不同，这里选择 fail-open；</li>
 *   <li><b>{@code eventName} / {@code type} 查询时按产品 TSL 回填</b>（契约 §8.2）：不入库，
 *       避免 TSL 改名后历史数据与新名字打架；TSL 事件定义表缓存 10 分钟。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventServiceImpl implements EventService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long DEVICE_CACHE_MINUTES = 60;
    private static final long TSL_CACHE_MINUTES = 10;

    private final DeviceEventMapper eventMapper;
    private final DeviceMapper deviceMapper;
    private final ProductMapper productMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    /** F2/BUG-27：事件入口同样要兜住「停用后仍持有长连接」的设备 */
    private final AuthService authService;

    @Override
    public void receive(EventRequest request) {
        try {
            if (request.getDeviceKey() == null) {
                log.warn("事件上报缺 device_key，丢弃: {}", request);
                return;
            }
            Device device = resolveDevice(request.getDeviceKey());
            if (device == null) {
                log.warn("事件上报的设备不存在，丢弃: {}", request.getDeviceKey());
                return;
            }

            // F2 停用拦截（BUG-27）：与遥测入口（TelemetryServiceImpl#processSync）同构。
            // 认证回调只拦得住**新连接**，停用前已建立的长连接必须靠每个入口各自兜底，
            // 否则「停用」不彻底 —— 事件链路此前漏的正是这道闸。
            // 语义上**返回 200 静默丢弃**（不抛异常）：事件是"已发生"的事实，
            // 且本接口是 EMQX webhook，抛错会被判为投递失败并触发重投，反而放大脏数据。
            if (!authService.isDeviceEnabled(request.getDeviceKey())) {
                log.warn("事件上报的设备/所属产品已停用，丢弃: {}", request.getDeviceKey());
                return;
            }

            // 事件字段藏在 payload 里；EMQX 可能渲染成 JSON 对象或「转义后的 JSON 字符串」两种形态
            Map<String, Object> payloadMap = parsePayloadFlexible(request.getPayload());
            String eventKey = payloadMap == null ? null : stringOf(payloadMap.get("event_key"));
            if (eventKey == null) {
                log.warn("事件上报 payload 缺 event_key，丢弃: {}", request.getPayload());
                return;
            }
            Object params = payloadMap.get("params");

            DeviceEvent e = new DeviceEvent();
            e.setDeviceId(device.getId());
            e.setEventKey(eventKey);
            e.setParams(params == null ? null : objectMapper.writeValueAsString(params));
            long ts = request.getTs() == null ? System.currentTimeMillis() : request.getTs();
            e.setTs(LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault()));
            eventMapper.insert(e);
        } catch (Exception ex) {
            log.warn("事件落库失败（已忽略，不阻塞 EMQX）: err={}, req={}", ex.getMessage(), request);
        }
    }

    @Override
    public Map<String, Object> page(String deviceKey, Integer page, Integer size, String eventKey) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 20 : Math.min(size, 100);

        Map<String, Object> result = new LinkedHashMap<>();
        Device device = resolveDevice(deviceKey);
        if (device == null) {
            result.put("total", 0);
            result.put("items", List.of());
            return result;
        }

        List<Map<String, Object>> rows = eventMapper.selectPage(device.getId(), eventKey, (p - 1) * s, s);
        Map<String, Map<String, String>> defs = loadEventDefs(device.getProductKey());

        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            String key = String.valueOf(r.get("eventKey"));
            Map<String, String> def = defs.get(key);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.get("id"));
            item.put("event_key", key);
            item.put("event_name", def != null ? def.get("name") : key);
            item.put("type", def != null ? def.get("type") : "info");
            item.put("params", parseParams(r.get("params")));
            item.put("ts", formatTs(r.get("ts")));
            items.add(item);
        }

        result.put("total", eventMapper.countByFilter(device.getId(), eventKey));
        result.put("items", items);
        return result;
    }

    // ===== 内部工具 =====

    /** device_key → Device（缓存 60 分钟，与遥测/规则链路共用 {@code dev:} 键空间） */
    private Device resolveDevice(String deviceKey) {
        String cacheKey = "dev:" + deviceKey;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return objectMapper.readValue(cached, Device.class);
            }
        } catch (Exception ignored) {
        }
        Device device = deviceMapper.selectOne(
                new LambdaQueryWrapper<Device>().eq(Device::getDeviceKey, deviceKey));
        if (device != null) {
            try {
                redisTemplate.opsForValue().set(cacheKey,
                        objectMapper.writeValueAsString(device), Duration.ofMinutes(DEVICE_CACHE_MINUTES));
            } catch (Exception ignored) {
            }
        }
        return device;
    }

    /** 产品 TSL 的 events 定义表：event_key → {name, type}（缓存 10 分钟） */
    private Map<String, Map<String, String>> loadEventDefs(String productKey) {
        String cacheKey = "tsl:events:" + productKey;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return objectMapper.readValue(cached,
                        new TypeReference<Map<String, Map<String, String>>>() {});
            }
        } catch (Exception ignored) {
        }

        Map<String, Map<String, String>> defs = new LinkedHashMap<>();
        try {
            Product product = productMapper.selectOne(
                    new LambdaQueryWrapper<Product>().eq(Product::getProductKey, productKey));
            if (product != null && product.getTsl() != null) {
                Map<String, Object> tsl = objectMapper.readValue(product.getTsl(),
                        new TypeReference<Map<String, Object>>() {});
                Object events = tsl.get("events");
                if (events instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> ev) {
                            String key = String.valueOf(ev.get("key"));
                            Object name = ev.get("name");
                            Object type = ev.get("type");
                            Map<String, String> def = new LinkedHashMap<>();
                            def.put("name", name == null ? key : String.valueOf(name));
                            def.put("type", type == null ? "info" : String.valueOf(type));
                            defs.put(key, def);
                        }
                    }
                }
            }
            redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(defs),
                    Duration.ofMinutes(TSL_CACHE_MINUTES));
        } catch (Exception e) {
            log.warn("TSL 事件定义解析失败: {}", e.getMessage());
        }
        return defs;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseParams(Object raw) {
        if (raw == null) return Map.of();
        if (raw instanceof Map<?, ?> m) {
            try {
                return objectMapper.convertValue(m, new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {
            }
        }
        try {
            return objectMapper.readValue(String.valueOf(raw), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * payload 兼容解析：EMQX 规则输出的 payload 可能是「JSON 对象」或「转义后的 JSON 字符串」，
     * 两种形态都要能解 —— 字符串形态直接按 Map 反序列化会失败，这就是首版事件不落库的根因。
     */
    private Map<String, Object> parsePayloadFlexible(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Map<?, ?> m) {
            try {
                return objectMapper.convertValue(m, new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {
            }
        }
        try {
            return objectMapper.readValue(String.valueOf(raw), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private String stringOf(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private String formatTs(Object ts) {
        if (ts instanceof LocalDateTime t) return t.format(TS);
        return String.valueOf(ts).replace('T', ' ');
    }
}
