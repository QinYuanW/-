package com.iot.platform.util;

import com.iot.platform.common.BizException;
import com.iot.platform.common.ErrorCode;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 物模型（TSL）结构校验。
 *
 * <p><b>为什么抽出来</b>：这段规则原先私有在 {@code ProductServiceImpl} 里。通用物模型模板
 * （{@code tsl_template.tsl}）与产品物模型（{@code product.tsl}）是<b>同构</b>的，
 * 两边若各写一份校验，规则迟早漂移 —— 典型后果是"模板存得进去、套用到产品时被拒"，
 * 或者反过来"产品报错但模板不报"，用户完全无从理解。
 * 所以规则只留这一份，产品和模板共用。
 *
 * <h3>校验内容</h3>
 * <ul>
 *   <li>{@code properties} 必须是数组且必填（可为空数组）；{@code services}/{@code events} 可缺省</li>
 *   <li>{@code key} 非空、仅 {@code [A-Za-z0-9_]{1,64}}，且 <b>三段之间全局唯一</b>
 *       （key 会进入 Redis 影子 field 与 MQTT 报文，跨段重名会互相覆盖）</li>
 *   <li>{@code name} 非空</li>
 *   <li>属性的 {@code type} ∈ int/float/bool/string/enum；{@code access} ∈ readonly/readwrite</li>
 *   <li>{@code type=enum} 必须配 {@code elements}，每项含 {@code value} 与 {@code key}</li>
 * </ul>
 *
 * <p>报错信息一律带位置（如 {@code properties[2].type ...}），因为这是给用户看的 ——
 * 只说"物模型不合法"等于没说。
 */
public final class TslValidator {

    private TslValidator() {
    }

    /** 属性数据类型白名单 */
    public static final Set<String> ALLOWED_TYPES = Set.of("int", "float", "bool", "string", "enum");

    /** 属性读写权限白名单 */
    public static final Set<String> ALLOWED_ACCESS = Set.of("readonly", "readwrite");

    /**
     * 校验物模型结构，不合法直接抛 {@link BizException}（code=4001）。
     *
     * @param tsl 物模型（properties / services / events）
     */
    public static void validate(Map<String, Object> tsl) {
        if (tsl == null || tsl.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "tsl 不能为空");
        }
        if (!(tsl.get("properties") instanceof List)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "tsl.properties 必须是数组（可为空）");
        }
        Set<String> seen = new HashSet<>();
        for (String section : List.of("properties", "services", "events")) {
            Object raw = tsl.get(section);
            if (raw == null) {
                continue; // services/events 可缺省
            }
            if (!(raw instanceof List)) {
                throw new BizException(ErrorCode.PARAM_ERROR, "tsl." + section + " 必须是数组");
            }
            int i = 0;
            for (Object o : (List<?>) raw) {
                String at = section + "[" + i++ + "]";
                if (!(o instanceof Map)) {
                    throw new BizException(ErrorCode.PARAM_ERROR, at + " 必须是对象");
                }
                Map<?, ?> item = (Map<?, ?>) o;

                String key = str(item.get("key"));
                if (key == null || key.isBlank()) {
                    throw new BizException(ErrorCode.PARAM_ERROR, at + ".key 不能为空");
                }
                if (!key.matches("[A-Za-z0-9_]{1,64}")) {
                    throw new BizException(ErrorCode.PARAM_ERROR,
                            at + ".key 仅允许字母/数字/下划线（≤64）: " + key);
                }
                if (!seen.add(key)) {
                    throw new BizException(ErrorCode.PARAM_ERROR, at + ".key 重复: " + key);
                }
                if (item.get("name") == null || String.valueOf(item.get("name")).isBlank()) {
                    throw new BizException(ErrorCode.PARAM_ERROR, at + ".name 不能为空");
                }
                if ("properties".equals(section)) {
                    String type = str(item.get("type"));
                    if (!ALLOWED_TYPES.contains(type)) {
                        throw new BizException(ErrorCode.PARAM_ERROR,
                                at + ".type 仅允许 int/float/bool/string/enum，实际: " + type);
                    }
                    if ("enum".equals(type)) {
                        Object elems = item.get("elements");
                        if (!(elems instanceof List) || ((List<?>) elems).isEmpty()) {
                            throw new BizException(ErrorCode.PARAM_ERROR,
                                    at + ".type=enum 时必须配置 elements 枚举项");
                        }
                        for (Object e : (List<?>) elems) {
                            if (!(e instanceof Map) || ((Map<?, ?>) e).get("value") == null
                                    || str(((Map<?, ?>) e).get("key")).isBlank()) {
                                throw new BizException(ErrorCode.PARAM_ERROR,
                                        at + ".elements 每项需含 value 与 key（key 为展示文本）");
                            }
                        }
                    }
                    String access = str(item.get("access"));
                    if (access == null) {
                        access = "readonly";
                    }
                    if (!ALLOWED_ACCESS.contains(access)) {
                        throw new BizException(ErrorCode.PARAM_ERROR,
                                at + ".access 仅允许 readonly/readwrite，实际: " + access);
                    }
                }
            }
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
