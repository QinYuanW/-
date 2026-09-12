package com.iot.platform.controller;

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

    /** 更新某产品的物模型（校验通过后落库，并失效该产品下设备的影子 TSL 缓存） */
    @PutMapping("/products/{productKey}/tsl")
    public Result<Map<String, Object>> updateTsl(@PathVariable String productKey,
                                                 @RequestBody Map<String, Object> tsl) {
        return Result.ok(productService.updateTsl(productKey, tsl));
    }
}
