package com.iot.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.dto.CommandRequest;
import com.iot.platform.dto.TelemetryRequest;
import com.iot.platform.entity.Device;
import com.iot.platform.entity.DeviceAlarm;
import com.iot.platform.entity.DeviceRule;
import com.iot.platform.mapper.DeviceMapper;
import com.iot.platform.mapper.DeviceRuleMapper;
import com.iot.platform.service.AlarmService;
import com.iot.platform.service.CommandService;
import com.iot.platform.service.RuleEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * E3 规则引擎实现：阈值评估 → 告警 → 联动下发。
 *
 * <h3>三个关键设计决定</h3>
 * <ol>
 *   <li><b>挂在落库之后、且全量吞异常</b>：规则评估是"旁路"，绝不能因为一条规则写错
 *       就影响遥测入库这条主链路（也不该让消息进重试阶梯）。</li>
 *   <li><b>冷却抑制（cooldownSec）</b>：设备每秒上报，温度持续超标时若不抑制，
 *       一分钟就能刷出 60 条重复告警。用 Redis {@code SETNX} 做原子窗口判定。</li>
 *   <li><b>联动下发取「期望状态语义」</b>（{@code queueIfOffline=true}）：与用户在控制台
 *       手点不同 —— 自动化联动的意图是"让温度降下来"，设备暂时离线并不使这个意图失效，
 *       因此允许挂起、待设备上线补发。这正是 E1 语义分家时为 E3 预留的判断。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleEngineServiceImpl implements RuleEngineService {

    private static final String COOLDOWN_PREFIX = "rule:cd:";
    private static final String RULE_CACHE_PREFIX = "rules:";
    /** 规则缓存 TTL：规则变更后最多 60s 生效，对阈值告警场景完全可接受 */
    private static final long RULE_CACHE_TTL_SEC = 60L;
    /** 空列表标记：用于区分「该产品确实没规则」与「缓存不存在」 */
    private static final String EMPTY_MARKER = "__EMPTY__";

    private final DeviceMapper deviceMapper;
    private final DeviceRuleMapper ruleMapper;
    private final AlarmService alarmService;
    private final CommandService commandService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void evaluate(TelemetryRequest request) {
        try {
            if (request.getDeviceKey() == null
                    || request.getProperties() == null
                    || request.getProperties().isEmpty()) {
                return;
            }

            Device device = resolveDevice(request.getDeviceKey());
            if (device == null) return;

            List<DeviceRule> rules = loadRulesCached(device.getProductKey());
            if (rules.isEmpty()) return;

            for (DeviceRule rule : rules) {
                try {
                    evaluateOne(rule, device, request);
                } catch (Exception e) {
                    // 单条规则出错不应影响其他规则
                    log.warn("规则[{}]评估失败: {}", rule.getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            // 规则引擎整体失败绝不能影响遥测主链路
            log.warn("规则引擎异常（已忽略）: device_key={}, err={}", request.getDeviceKey(), e.getMessage());
        }
    }

    @Override
    public void evictRuleCache(String productKey) {
        if (productKey == null || productKey.isEmpty()) return;
        try {
            redisTemplate.delete(RULE_CACHE_PREFIX + productKey);
            log.debug("已清规则缓存: product_key={}", productKey);
        } catch (Exception e) {
            log.debug("清除规则缓存失败: product_key={} ({})", productKey, e.getMessage());
        }
    }

    @Override
    public void evictCooldown(Long ruleId) {
        if (ruleId == null) return;
        // `rule:cd:{id}:*` 里的冒号是硬分隔符，天然不会让 id=1 命中 id=12（前缀对不上）
        String pattern = COOLDOWN_PREFIX + ruleId + ":*";
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(pattern).count(200).build())) {
            List<String> keys = new ArrayList<>();
            cursor.forEachRemaining(keys::add);
            if (!keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.debug("已清规则[{}]冷却键 {} 个（改完规则立即可重新触发）", ruleId, keys.size());
            }
        } catch (Exception e) {
            log.debug("清除规则冷却键失败: rule_id={} ({})", ruleId, e.getMessage());
        }
    }

    /**
     * 取该产品下「启用且未删除」的规则（Redis 缓存 60s）。
     *
     * <h3>为什么必须缓存（C2 收尾实测驱动）</h3>
     * 规则评估挂在<b>每条上报</b>上。实测单条处理 14.5ms 中，规则环节占 ~2~3ms，主因就是这里的一次
     * SELECT；而压测场景下绝大多数设备所属产品<b>根本没配规则</b>，却仍要白查一次库。
     *
     * <h3>两个关键细节</h3>
     * <ol>
     *   <li><b>空列表也要缓存</b>（用 {@link #EMPTY_MARKER} 标记）—— 否则「没规则的产品」每次上报
     *       照样查库，优化等于没做（这恰恰是压测中的大多数）；</li>
     *   <li><b>缓存读写失败一律回退查库</b> —— 只损失性能，不影响规则生效。</li>
     * </ol>
     */
    private List<DeviceRule> loadRulesCached(String productKey) {
        String cacheKey = RULE_CACHE_PREFIX + productKey;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                if (EMPTY_MARKER.equals(cached)) return List.of();
                return objectMapper.readValue(cached, new TypeReference<List<DeviceRule>>() {});
            }
        } catch (Exception e) {
            log.debug("规则缓存读取失败，回退查库: {}", e.getMessage());
        }

        List<DeviceRule> rules = ruleMapper.selectList(new LambdaQueryWrapper<DeviceRule>()
                .eq(DeviceRule::getProductKey, productKey)
                .eq(DeviceRule::getEnabled, 1)
                .eq(DeviceRule::getDeleted, 0));

        try {
            redisTemplate.opsForValue().set(cacheKey,
                    rules.isEmpty() ? EMPTY_MARKER : objectMapper.writeValueAsString(rules),
                    Duration.ofSeconds(RULE_CACHE_TTL_SEC));
        } catch (Exception e) {
            log.debug("规则缓存写入失败: {}", e.getMessage());
        }
        return rules;
    }

    private void evaluateOne(DeviceRule rule, Device device, TelemetryRequest request) {
        // 规则限定到其他设备时跳过（deviceKey 为空 = 作用于全产品）
        if (rule.getDeviceKey() != null && !rule.getDeviceKey().isEmpty()
                && !rule.getDeviceKey().equals(request.getDeviceKey())) {
            return;
        }

        Object raw = request.getProperties().get(rule.getPropertyKey());
        if (raw == null) return;

        Double actual = toDouble(raw);
        if (actual == null) return;

        double threshold = rule.getThreshold() == null ? 0 : rule.getThreshold().doubleValue();
        if (!compare(actual, rule.getOperator(), threshold)) return;

        // 冷却窗口内已告警过则跳过（温度持续超标 ≠ 需要持续告警）
        if (!acquireCooldown(rule, device.getId())) return;

        String op = rule.getOperator();
        String content = String.format("%s %s %s，实际上报 %s",
                rule.getPropertyKey(), op, trim(rule.getThreshold()), trim(BigDecimal.valueOf(actual)));

        DeviceAlarm alarm = new DeviceAlarm();
        alarm.setDeviceId(device.getId());
        alarm.setRuleId(rule.getId());
        alarm.setAlarmType(rule.getAlarmType());
        alarm.setLevel(rule.getLevel());
        alarm.setRuleName(rule.getName());
        alarm.setContent(content);
        alarmService.raise(alarm);

        log.info("规则命中: rule={}, device={}, content={}", rule.getName(), device.getDeviceKey(), content);

        // 联动下发（action_type=1）
        if (Integer.valueOf(1).equals(rule.getActionType())) {
            dispatchAction(device, rule);
        }
    }

    /** 冷却窗口：同规则 + 同设备在 cooldownSec 内只告警一次 */
    private boolean acquireCooldown(DeviceRule rule, Long deviceId) {
        int seconds = rule.getCooldownSec() == null || rule.getCooldownSec() <= 0 ? 60 : rule.getCooldownSec();
        String key = COOLDOWN_PREFIX + rule.getId() + ":" + deviceId;
        Boolean first = redisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(seconds));
        return Boolean.TRUE.equals(first);
    }

    /** 解析规则里配置的 actionPayload 并下发 */
    private void dispatchAction(Device device, DeviceRule rule) {
        if (rule.getActionPayload() == null || rule.getActionPayload().isBlank()) {
            log.warn("规则[{}] 配置了联动但 action_payload 为空，跳过下发", rule.getName());
            return;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    rule.getActionPayload(), new TypeReference<Map<String, Object>>() {});

            CommandRequest cmd = new CommandRequest();
            cmd.setCommandType(1);
            cmd.setPayload(payload);
            // 自动化联动取期望状态语义：离线挂起、上线补发（见类注释第 3 点）
            cmd.setQueueIfOffline(true);

            commandService.dispatch(device.getDeviceKey(), cmd);
            log.info("规则联动下发: rule={}, device={}, payload={}", rule.getName(), device.getDeviceKey(), payload);
        } catch (Exception e) {
            log.warn("规则[{}]联动下发失败: {}", rule.getName(), e.getMessage());
        }
    }

    /**
     * device_key → Device。
     *
     * <p>规则评估挂在<b>每条上报</b>上，直查库会成为热点 —— 因此缓存整个 Device JSON（TTL 1h）。
     * 缓存读写失败一律回退查库，不因缓存问题影响主流程。
     */
    private Device resolveDevice(String deviceKey) {
        String cacheKey = "dev:" + deviceKey;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return objectMapper.readValue(cached, Device.class);
            }
        } catch (Exception e) {
            log.debug("设备缓存读取失败，回退查库: {}", e.getMessage());
        }

        Device device = deviceMapper.selectOne(
                new LambdaQueryWrapper<Device>().eq(Device::getDeviceKey, deviceKey));
        if (device != null) {
            try {
                redisTemplate.opsForValue().set(cacheKey,
                        objectMapper.writeValueAsString(device), Duration.ofHours(1));
            } catch (Exception e) {
                log.debug("设备缓存写入失败: {}", e.getMessage());
            }
        }
        return device;
    }

    private Double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;   // 非数值属性（字符串/布尔）不参与阈值比较
        }
    }

    private boolean compare(double actual, String operator, double threshold) {
        if (operator == null) return false;
        return switch (operator) {
            case ">" -> actual > threshold;
            case ">=" -> actual >= threshold;
            case "<" -> actual < threshold;
            case "<=" -> actual <= threshold;
            case "==" -> Double.compare(actual, threshold) == 0;
            case "!=" -> Double.compare(actual, threshold) != 0;
            default -> false;
        };
    }

    /** 去掉 BigDecimal 尾部多余的 0（50.0000 → 50） */
    private String trim(BigDecimal v) {
        return v == null ? "?" : v.stripTrailingZeros().toPlainString();
    }
}
