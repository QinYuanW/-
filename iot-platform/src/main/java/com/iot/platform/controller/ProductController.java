package com.iot.platform.controller;

import com.iot.platform.common.BizException;
import com.iot.platform.common.ErrorCode;
import com.iot.platform.common.Result;
import com.iot.platform.dto.ProductCreateRequest;
import com.iot.platform.dto.ProductVO;
import com.iot.platform.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * E5 产品与物模型管理接口。
 *
 * <p>路径在 {@code /api/v1/**} 下，受 {@code JwtInterceptor} 保护（控制台登录后携带 token 调用）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /** 产品列表（含已解析的物模型对象） */
    @GetMapping("/products")
    public Result<Map<String, Object>> list() {
        List<ProductVO> items = productService.listProducts();
        return Result.ok(Map.of("total", items.size(), "items", items));
    }

    /** 新建产品 */
    @PostMapping("/products")
    public Result<ProductVO> create(@RequestBody ProductCreateRequest request) {
        return Result.ok(productService.createProduct(request));
    }

    /** 读取某产品的物模型 */
    @GetMapping("/products/{productKey}/tsl")
    public Result<Map<String, Object>> tsl(@PathVariable String productKey) {
        return Result.ok(productService.getTsl(productKey));
    }

    /**
     * F2 启停产品：body {@code {"status": 0|1}}。
     *
     * <p>停用后该产品下**所有设备**会被拒绝 MQTT 接入（认证处按
     * {@code device.status && product.status} 实时合成，不批量改设备数据），
     * 数据与配置全部保留，恢复产品即恢复正常。
     */
    @PutMapping("/products/{productKey}/status")
    public Result<Void> updateStatus(@PathVariable String productKey,
                                     @RequestBody Map<String, Integer> body) {
        Integer status = body.get("status");
        if (status == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "缺少 status（0 停用 / 1 启用）");
        }
        productService.updateStatus(productKey, status);
        return Result.ok();
    }

    /** 更新某产品的物模型（校验通过后落库，并失效该产品下设备的影子 TSL 缓存） */
    @PutMapping("/products/{productKey}/tsl")
    public Result<Map<String, Object>> updateTsl(@PathVariable String productKey,
                                                 @RequestBody Map<String, Object> tsl) {
        return Result.ok(productService.updateTsl(productKey, tsl));
    }

    /** 评审 6.1：改产品名称（body {@code {"name": "..."}}）—— 纯展示变更，不动链路 */
    @PutMapping("/products/{productKey}")
    public Result<ProductVO> rename(@PathVariable String productKey,
                                    @RequestBody Map<String, Object> body) {
        Object name = body.get("name");
        if (name == null || String.valueOf(name).isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "缺少 name");
        }
        return Result.ok(productService.renameProduct(productKey, String.valueOf(name)));
    }

    /**
     * 评审 6.1：删除产品。
     *
     * <p>名下还有设备时会被拒绝（见 {@link ProductService#deleteProduct}）——
     * 这不是"多此一举的确认"，而是防止把设备指向一个不存在的产品、且再也救不回来。
     */
    @DeleteMapping("/products/{productKey}")
    public Result<Void> delete(@PathVariable String productKey) {
        productService.deleteProduct(productKey);
        return Result.ok();
    }
}
