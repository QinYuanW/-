package com.iot.platform.service;

import com.iot.platform.dto.ProductCreateRequest;
import com.iot.platform.dto.ProductVO;

import java.util.List;
import java.util.Map;

/**
 * E5 产品与物模型管理。
 *
 * <p>为什么需要它：前端控制台是 100% TSL 驱动的（属性卡片/控件/曲线全部按
 * {@code product.tsl} 动态生成），但此前 TSL 只能直接改数据库 —— E5 把
 * 「改物模型」变成一个 API 能力，新传感器才能真正做到「界面上点几下」。
 */
public interface ProductService {

    /** 产品列表（含已解析的 tsl 对象） */
    List<ProductVO> listProducts();

    /** 新建产品（product_key 留空自动生成；tsl 缺省为空物模型） */
    ProductVO createProduct(ProductCreateRequest request);

    /** 读取某产品的物模型 */
    Map<String, Object> getTsl(String productKey);

    /**
     * 更新某产品的物模型（先校验后落库）。
     *
     * <p>副作用：该产品下所有设备的 TSL 属性白名单缓存会被失效
     * （E1 影子依赖这份缓存，TTL 1h，不清会让新属性被拒/漏记）。
     */
    Map<String, Object> updateTsl(String productKey, Map<String, Object> tsl);

    /**
     * F2 启停产品。
     *
     * @param status 1 = 启用，0 = 停用
     *
     * <p>停用后该产品下**所有**设备的 MQTT 接入会被拒绝 —— 级联判断在认证处实时合成
     * （{@code device.status && product.status}），**不**批量改子设备的 status，
     * 这样产品恢复时每台设备的原状态能准确复现。
     *
     * <p>变更后立即失效产品状态缓存（认证回调 TTL 5min），让停用不用等缓存过期。
     */
    void updateStatus(String productKey, int status);
}
