package com.iot.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.common.BizException;
import com.iot.platform.common.ErrorCode;
import com.iot.platform.dto.DeviceRuleRequest;
import com.iot.platform.dto.DeviceRuleVO;
import com.iot.platform.entity.Device;
import com.iot.platform.entity.DeviceRule;
import com.iot.platform.entity.Product;
import com.iot.platform.mapper.DeviceAlarmMapper;
import com.iot.platform.mapper.DeviceMapper;
import com.iot.platform.mapper.DeviceRuleMapper;
import com.iot.platform.mapper.ProductMapper;
import com.iot.platform.service.DeviceRuleService;
import com.iot.platform.service.RuleEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 告警规则管理实现（E3 尾巴）。
 *
 * <h3>三个必须做对的点</h3>
 * <ol>
 *   <li><b>写操作后清缓存</b>：规则评估为省一次 SELECT 缓存了 {@code rules:{productKey}}（60s），
 *       不清就会出现「界面改了、上报还是老规则」的假故障；改了 {@code product_key}
 *       还要**连旧产品一起清**。同时清该规则的冷却键 {@code rule:cd:{id}:*}，
 *       否则改完立刻测会被上一次告警的抑制窗口挡住。</li>
 *   <li><b>{@code action_payload} 是 MySQL JSON 列</b>：空串不是合法 JSON（会报 3140
 *       <i>Invalid JSON text</i>），必须落 {@code NULL}；且 {@code action_type=1} 时
 *       必须是一个 JSON <b>对象</b>（联动下发按 {@code Map} 解析）。这两点交给数据库报错
 *       会非常难定位，所以在入口就拦住。</li>
 *   <li><b>编辑用显式 set 而非 {@code updateById}</b>：MyBatis-Plus 默认跳过 null 字段，
 *       而本表 {@code device_key} / {@code action_payload} 允许为 NULL ——
 *       用 {@code updateById} 会导致"从『告警并下发』改回『仅告警』后，
 *       旧 payload 仍留在库里"（清不掉）。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceRuleServiceImpl implements DeviceRuleService {

    /** 合法比较运算符（与 {@code RuleEngineServiceImpl.compare()} 支持的集合一致） */
    private static final Set<String> OPERATORS = Set.of(">", ">=", "<", "<=", "==", "!=");
    private static final String DEFAULT_ALARM_TYPE = "规则告警";

    private final DeviceRuleMapper ruleMapper;
    private final DeviceAlarmMapper alarmMapper;
    private final DeviceMapper deviceMapper;
    private final ProductMapper productMapper;
    private final RuleEngineService ruleEngineService;
    private final ObjectMapper objectMapper;

    @Override
    public Page<DeviceRuleVO> listRules(int page, int size, String productKey, String keyword, Integer enabled) {
        LambdaQueryWrapper<DeviceRule> q = new LambdaQueryWrapper<DeviceRule>()
                .eq(productKey != null && !productKey.isEmpty(), DeviceRule::getProductKey, productKey)
                .like(keyword != null && !keyword.isEmpty(), DeviceRule::getName, keyword)
                .eq(enabled != null, DeviceRule::getEnabled, enabled)
                .orderByDesc(DeviceRule::getId);

        Page<DeviceRule> p = ruleMapper.selectPage(new Page<>(page, size), q);
        List<DeviceRuleVO> vos = p.getRecords().stream().map(DeviceRuleVO::from).toList();
        fillAlarmCount(vos);

        Page<DeviceRuleVO> result = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        result.setRecords(vos);
        return result;
    }

    @Override
    public DeviceRuleVO getById(Long id) {
        DeviceRule rule = ruleMapper.selectById(id);
        if (rule == null) throw new BizException(ErrorCode.PARAM_ERROR, "规则不存在");
        DeviceRuleVO vo = DeviceRuleVO.from(rule);
        fillAlarmCount(List.of(vo));
        return vo;
    }

    @Override
    public Long create(DeviceRuleRequest request) {
        DeviceRule rule = normalize(request);
        ruleMapper.insert(rule);

        ruleEngineService.evictRuleCache(rule.getProductKey());
        log.info("新建告警规则[{}] id={} product={} {} {} {}",
                rule.getName(), rule.getId(), rule.getProductKey(),
                rule.getPropertyKey(), rule.getOperator(), rule.getThreshold());
        return rule.getId();
    }

    @Override
    public void update(Long id, DeviceRuleRequest request) {
        DeviceRule exist = ruleMapper.selectById(id);
        if (exist == null) throw new BizException(ErrorCode.PARAM_ERROR, "规则不存在");

        DeviceRule rule = normalize(request);

        // 显式 set 全字段（含置空 device_key / action_payload），不走 updateById 的"跳过 null"
        ruleMapper.update(null, new LambdaUpdateWrapper<DeviceRule>()
                .eq(DeviceRule::getId, id)
                .set(DeviceRule::getName, rule.getName())
                .set(DeviceRule::getProductKey, rule.getProductKey())
                .set(DeviceRule::getDeviceKey, rule.getDeviceKey())
                .set(DeviceRule::getPropertyKey, rule.getPropertyKey())
                .set(DeviceRule::getOperator, rule.getOperator())
                .set(DeviceRule::getThreshold, rule.getThreshold())
                .set(DeviceRule::getLevel, rule.getLevel())
                .set(DeviceRule::getAlarmType, rule.getAlarmType())
                .set(DeviceRule::getActionType, rule.getActionType())
                .set(DeviceRule::getActionPayload, rule.getActionPayload())
                .set(DeviceRule::getCooldownSec, rule.getCooldownSec())
                .set(DeviceRule::getEnabled, rule.getEnabled()));

        // product_key 可能被改过 → 新旧产品缓存都要清，否则旧产品下还留着这条规则的旧副本
        ruleEngineService.evictRuleCache(exist.getProductKey());
        ruleEngineService.evictRuleCache(rule.getProductKey());
        ruleEngineService.evictCooldown(id);

        log.info("编辑告警规则[{}] id={}（product {} → {}）",
                rule.getName(), id, exist.getProductKey(), rule.getProductKey());
    }

    @Override
    public void updateStatus(Long id, Integer enabled) {
        if (enabled == null || (enabled != 0 && enabled != 1)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "enabled 只能是 0（停用）或 1（启用）");
        }
        DeviceRule exist = ruleMapper.selectById(id);
        if (exist == null) throw new BizException(ErrorCode.PARAM_ERROR, "规则不存在");

        ruleMapper.update(null, new LambdaUpdateWrapper<DeviceRule>()
                .eq(DeviceRule::getId, id)
                .set(DeviceRule::getEnabled, enabled));

        ruleEngineService.evictRuleCache(exist.getProductKey());
        // 重新启用时清冷却，保证"启用后立刻上报就能看到告警"
        if (enabled == 1) ruleEngineService.evictCooldown(id);

        log.info("{}告警规则[{}] id={}", enabled == 1 ? "启用" : "停用", exist.getName(), id);
    }

    @Override
    public void delete(Long id) {
        DeviceRule exist = ruleMapper.selectById(id);
        if (exist == null) throw new BizException(ErrorCode.PARAM_ERROR, "规则不存在");

        // 逻辑删除（全局 logic-delete-field=deleted）。历史告警不删：device_alarm.rule_name
        // 已冗余了规则名，删规则后旧告警仍可读，只是 rule_id 变成孤儿引用（无外键，物理上无害）。
        ruleMapper.deleteById(id);

        ruleEngineService.evictRuleCache(exist.getProductKey());
        ruleEngineService.evictCooldown(id);
        log.info("删除告警规则[{}] id={}", exist.getName(), id);
    }

    // ─────────────────────────── 内部 ───────────────────────────

    /** 校验入参并归一成实体（三处硬约束：产品/设备存在性、运算符集合、JSON payload 形态） */
    private DeviceRule normalize(DeviceRuleRequest req) {
        String name = trim(req.getName());
        if (name == null) throw new BizException(ErrorCode.PARAM_ERROR, "规则名称不能为空");
        if (name.length() > 64) throw new BizException(ErrorCode.PARAM_ERROR, "规则名称不能超过 64 字符");

        String productKey = trim(req.getProductKey());
        if (productKey == null) throw new BizException(ErrorCode.PARAM_ERROR, "必须选择所属产品");
        if (productMapper.selectOne(new LambdaQueryWrapper<Product>()
                .eq(Product::getProductKey, productKey)) == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "产品不存在：" + productKey);
        }

        String deviceKey = trim(req.getDeviceKey());
        if (deviceKey != null) {
            // 限定到单台设备时校验存在性与归属 —— 否则会配出一条"永远不会命中"的规则，
            // 且因为不报错，排查起来全靠猜
            Device d = deviceMapper.selectOne(new LambdaQueryWrapper<Device>()
                    .eq(Device::getDeviceKey, deviceKey));
            if (d == null) throw new BizException(ErrorCode.PARAM_ERROR, "设备不存在：" + deviceKey);
            if (!productKey.equals(d.getProductKey())) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "设备 " + deviceKey + " 不属于产品 " + productKey);
            }
        }

        String propertyKey = trim(req.getPropertyKey());
        if (propertyKey == null) throw new BizException(ErrorCode.PARAM_ERROR, "必须选择监测属性");

        String operator = trim(req.getOperator());
        if (operator == null || !OPERATORS.contains(operator)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "运算符非法（仅支持 > >= < <= == !=）：" + operator);
        }

        if (req.getThreshold() == null) throw new BizException(ErrorCode.PARAM_ERROR, "阈值不能为空");

        int level = req.getLevel() == null ? 2 : req.getLevel();
        if (level < 1 || level > 3) {
            throw new BizException(ErrorCode.PARAM_ERROR, "告警级别只能是 1（严重）/ 2（一般）/ 3（提示）");
        }

        int actionType = req.getActionType() == null ? 0 : req.getActionType();
        if (actionType != 0 && actionType != 1) {
            throw new BizException(ErrorCode.PARAM_ERROR, "action_type 只能是 0（仅告警）或 1（告警并下发）");
        }

        String actionPayload = trim(req.getActionPayload());
        if (actionType == 1) {
            if (actionPayload == null) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "选择「告警并下发指令」时必须填写下发内容（JSON 对象，如 {\"switch\":false}）");
            }
            try {
                JsonNode node = objectMapper.readTree(actionPayload);
                if (!node.isObject()) {
                    throw new BizException(ErrorCode.PARAM_ERROR,
                            "下发内容必须是 JSON 对象，如 {\"switch\":false}（不能是数组或裸值）");
                }
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                throw new BizException(ErrorCode.PARAM_ERROR, "下发内容不是合法 JSON：" + e.getMessage());
            }
        } else {
            // 仅告警 → 清空，避免残留旧 payload 造成误解
            actionPayload = null;
        }

        int cooldown = req.getCooldownSec() == null ? 60 : req.getCooldownSec();
        if (cooldown < 0) throw new BizException(ErrorCode.PARAM_ERROR, "冷却窗口不能为负数");

        DeviceRule rule = new DeviceRule();
        rule.setName(name);
        rule.setProductKey(productKey);
        rule.setDeviceKey(deviceKey);
        rule.setPropertyKey(propertyKey);
        rule.setOperator(operator);
        rule.setThreshold(req.getThreshold());
        rule.setLevel(level);
        rule.setAlarmType(trim(req.getAlarmType()) == null ? DEFAULT_ALARM_TYPE : trim(req.getAlarmType()));
        rule.setActionType(actionType);
        rule.setActionPayload(actionPayload);
        rule.setCooldownSec(cooldown);
        rule.setEnabled(req.getEnabled() == null || req.getEnabled() == 1 ? 1 : 0);
        return rule;
    }

    /**
     * 批量填「累计告警数」。
     *
     * <p>用一次 {@code GROUP BY rule_id} 取全量，而不是逐条 count ——
     * 列表页默认 20 条，逐条查会变成 21 次往返（典型 N+1）。
     */
    private void fillAlarmCount(List<DeviceRuleVO> vos) {
        if (vos.isEmpty()) return;
        try {
            List<Long> ids = vos.stream().map(DeviceRuleVO::getId).toList();
            Map<Long, Long> counts = new HashMap<>();
            for (Map<String, Object> row : alarmMapper.countByRuleIds(ids)) {
                Object rid = row.get("ruleId");
                Object cnt = row.get("cnt");
                if (rid instanceof Number n) {
                    counts.put(n.longValue(), cnt instanceof Number c ? c.longValue() : 0L);
                }
            }
            vos.forEach(v -> v.setAlarmCount(counts.getOrDefault(v.getId(), 0L)));
        } catch (Exception e) {
            // 计数是展示增强，取不到就留 0，绝不因此让列表接口失败
            log.debug("统计规则告警数失败: {}", e.getMessage());
        }
    }

    private String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
