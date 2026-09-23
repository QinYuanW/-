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
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.iot.platform.service.AuthService;
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
    /** 用于状态变更后立即失效产品启用缓存（认证回调侧） */
    private final AuthService authService;
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
    /**
     * 物模型校验：规则已抽到 {@link com.iot.platform.util.TslValidator}，
     * 与「通用物模型模板」共用同一份 —— 否则两处规则会漂移成
     * "模板存得进、套到产品被拒"这类无从理解的错误。
     */
    private void validateTsl(Map<String, Object> tsl) {
        com.iot.platform.util.TslValidator.validate(tsl);
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

    @Override
    public void updateStatus(String productKey, int status) {
        if (status != 0 && status != 1) {
            throw new BizException(ErrorCode.PARAM_ERROR, "status 只能是 0（停用）或 1（启用）");
        }
        Product exist = productMapper.selectOne(
                new LambdaQueryWrapper<Product>().eq(Product::getProductKey, productKey));
        if (exist == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "产品不存在: " + productKey);
        }

        productMapper.update(null, new LambdaUpdateWrapper<Product>()
                .eq(Product::getProductKey, productKey)
                .set(Product::getStatus, status));

        // 立即失效产品状态缓存（认证/遥测侧 TTL 5min），让停用/启用不必等缓存自然过期。
        //
        // ⚠️ 这里**只需清这一个 key**，无需扇出清理该产品下 N 台设备的 auth 缓存 ——
        // 因为 v3 起设备缓存只存 {secret|deviceEnabled|product_key}，产品启用状态一律
        // 现查 prod:enabled:（见 AuthServiceImpl 的缓存格式说明）。
        // v2 时代产品状态曾缓存在设备条目里，导致停用产品在该 key 自然过期前（最长 300s）
        // 仍然放行设备接入 —— 2026-09-15 已实测复现并修复（BUG-26）。
        authService.evictProductCache(productKey);
        log.info("产品[{}] 状态 → {}{}", productKey, status,
                status == 1 ? "（启用）" : "（停用：该产品下所有设备将无法接入）");
    }

    @Override
    public ProductVO renameProduct(String productKey, String name) {
        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "缺少产品名称");
        }
        Product exist = productMapper.selectOne(
                new LambdaQueryWrapper<Product>().eq(Product::getProductKey, productKey));
        if (exist == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "产品不存在: " + productKey);
        }
        productMapper.update(null, new LambdaUpdateWrapper<Product>()
                .eq(Product::getProductKey, productKey)
                .set(Product::getName, name.trim())
                .set(Product::getUpdatedAt, LocalDateTime.now()));
        log.info("产品[{}] 改名：{} → {}", productKey, exist.getName(), name.trim());
        return ProductVO.from(productMapper.selectOne(
                new LambdaQueryWrapper<Product>().eq(Product::getProductKey, productKey)), objectMapper);
    }

    @Override
    public void deleteProduct(String productKey) {
        Product exist = productMapper.selectOne(
                new LambdaQueryWrapper<Product>().eq(Product::getProductKey, productKey));
        if (exist == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "产品不存在: " + productKey);
        }
        // 护栏：名下还有设备就拒绝删除。否则那些设备会指向一个不存在的产品 ——
        // 认证（级联判断读产品状态）、物模型解析、产品级联停用全部失效，
        // 而界面上没有任何入口能把它们救回来。正确顺序是先删设备或把它们迁到别的产品。
        // （Device 带 @TableLogic，这里的 count 自动只算未删除的）
        Long alive = deviceMapper.selectCount(
                new LambdaQueryWrapper<Device>().eq(Device::getProductKey, productKey));
        if (alive != null && alive > 0) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "该产品下还有 " + alive + " 台设备，不能删除；请先删除这些设备或把它们迁到其它产品");
        }
        productMapper.delete(new LambdaQueryWrapper<Product>().eq(Product::getProductKey, productKey));
        // 清产品启用缓存：否则该 key 在 TTL 内仍会被判成"启用"
        authService.evictProductCache(productKey);
        log.info("产品[{}] 已删除（逻辑删除，库里数据仍在）", productKey);
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
