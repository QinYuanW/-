package com.iot.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.common.BizException;
import com.iot.platform.common.ErrorCode;
import com.iot.platform.dto.ProductCreateRequest;
import com.iot.platform.dto.ProductVO;
import com.iot.platform.entity.Device;
import com.iot.platform.entity.Product;
import com.iot.platform.mapper.DeviceMapper;
import com.iot.platform.mapper.ProductMapper;
import com.iot.platform.service.ProductService;
import com.iot.platform.service.ShadowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * E5 产品与物模型管理实现。
 *
 * <p>TSL 校验口径（与前端渲染逻辑对齐）：
 * <ul>
 *   <li>{@code properties} 必须是数组（可为空）；{@code services}/{@code events} 可缺省；</li>
 *   <li>属性 {@code type} 仅允许 int/float/bool/string/enum —— HistoryChart 只给 int/float 画曲线，
 *       ControlPanel 按类型生成控件（enum 用下拉、数值用步长）；</li>
 *   <li>属性 {@code access} 仅允许 readonly/readwrite —— readwrite 才会渲染下发控件；</li>
 *   <li>type=enum 的属性必须配置 {@code elements}（[{value, key}] 枚举项列表，key 为展示文本）；</li>
 *   <li>所有 key 全局（三段内）唯一、仅允许字母/数字/下划线（key 会进入 Redis 影子 field 与 MQTT 报文）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductMapper productMapper;
    private final DeviceMapper deviceMapper;
    private final ShadowService shadowService;
    private final ObjectMapper objectMapper;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> ALLOWED_TYPES = Set.of("int", "float", "bool", "string", "enum");
    private static final Set<String> ALLOWED_ACCESS = Set.of("readonly", "readwrite");
    /** MVP 单演示项目（seed id=1）：新建产品缺省挂到这里 */
    private static final long DEFAULT_PROJECT_ID = 1L;

    @Override
    public List<ProductVO> listProducts() {
        return productMapper.selectList(new LambdaQueryWrapper<Product>().orderByAsc(Product::getId))
                .stream()
                .map(p -> ProductVO.from(p, objectMapper))
                .collect(Collectors.toList());
    }

    @Override
    public ProductVO createProduct(ProductCreateRequest request) {
        String name = request.getName() == null ? "" : request.getName().trim();
        if (name.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "产品名称不能为空");
        }

        String productKey = request.getProductKey() == null ? "" : request.getProductKey().trim();
        if (productKey.isEmpty()) {
            productKey = "p_" + randomHex(4);
        }
        if (!productKey.matches("[A-Za-z0-9_]{1,32}")) {
            throw new BizException(ErrorCode.PARAM_ERROR, "product_key 仅允许字母/数字/下划线，长度 ≤32");
        }
        if (productMapper.selectCount(new LambdaQueryWrapper<Product>()
                .eq(Product::getProductKey, productKey)) > 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "product_key 已存在: " + productKey);
        }

        Map<String, Object> tsl = request.getTsl() != null ? request.getTsl() : emptyTsl();
        validateTsl(tsl);

        Product p = new Product();
        p.setProductKey(productKey);
        p.setName(name);
        p.setProjectId(request.getProjectId() != null ? request.getProjectId() : DEFAULT_PROJECT_ID);
        p.setNodeType(1);
        p.setProtocol("MQTT");
        p.setDataFormat("JSON");
        p.setTsl(toJson(tsl));
        p.setStatus(1);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        productMapper.insert(p);

        log.info("product created: product_key={}, name={}", productKey, name);
        return ProductVO.from(p, objectMapper);
    }

    @Override
    public Map<String, Object> getTsl(String productKey) {
        return parseTsl(requireProduct(productKey).getTsl());
    }

    @Override
    public Map<String, Object> updateTsl(String productKey, Map<String, Object> tsl) {
        Product p = requireProduct(productKey);
        validateTsl(tsl);

        p.setTsl(toJson(tsl));
        p.setUpdatedAt(LocalDateTime.now());
        productMapper.updateById(p);

        // 物模型变了，E1 影子的「TSL 属性白名单」缓存必须失效 —— 否则新增属性会在
        // 最长 1h 内被影子当作未声明属性拒绝/漏记（这是个隐蔽的跨模块时序坑）。
        List<String> deviceKeys = deviceMapper.selectList(new LambdaQueryWrapper<Device>()
                        .eq(Device::getProductKey, productKey))
                .stream().map(Device::getDeviceKey).collect(Collectors.toList());
        shadowService.invalidateTslCache(deviceKeys);

        log.info("product tsl updated: product_key={}, properties={}, devices_invalidated={}",
                productKey, countOf(tsl.get("properties")), deviceKeys.size());
        return tsl;
    }

    // ------------------------------------------------------------------ 内部

    private Product requireProduct(String productKey) {
        Product p = productMapper.selectOne(
                new LambdaQueryWrapper<Product>().eq(Product::getProductKey, productKey));
        if (p == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "产品不存在: " + productKey);
        }
        return p;
    }

    /** 物模型校验：结构 + key 唯一性 + 类型/读写权限枚举（详见类注释口径） */
    private void validateTsl(Map<String, Object> tsl) {
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

    private Map<String, Object> emptyTsl() {
        Map<String, Object> tsl = new LinkedHashMap<>();
        tsl.put("properties", new ArrayList<>());
        tsl.put("services", new ArrayList<>());
        tsl.put("events", new ArrayList<>());
        return tsl;
    }

    private Map<String, Object> parseTsl(String json) {
        if (json == null || json.isBlank()) {
            return emptyTsl();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("tsl parse failed, fallback to empty: {}", e.getMessage());
            return emptyTsl();
        }
    }

    private String toJson(Map<String, Object> tsl) {
        try {
            return objectMapper.writeValueAsString(tsl);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "tsl 序列化失败");
        }
    }

    private int countOf(Object list) {
        return list instanceof List ? ((List<?>) list).size() : 0;
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private String randomHex(int bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes; i++) {
            sb.append(String.format("%02x", RANDOM.nextInt(256)));
        }
        return sb.toString();
    }
}
