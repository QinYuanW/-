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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
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

            List<DeviceRule> rules = ruleMapper.selectList(new LambdaQueryWrapper<DeviceRule>()
                    .eq(DeviceRule::getProductKey, device.getProductKey())
                    .eq(DeviceRule::getEnabled, 1)
                    .eq(DeviceRule::getDeleted, 0));
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
