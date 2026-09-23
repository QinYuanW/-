package com.iot.platform.dto;

import lombok.Data;

import java.util.Map;

/**
 * 通用物模型模板的创建 / 更新入参。
 *
 * <p>{@code tsl} 用 {@code Map} 而不是字符串：让它与 {@code product.tsl} 走同一条
 * 「Map → Jackson → json 列」的路径，避免出现"模板里是转义过的 JSON 字符串"这种二次编码。
 */
@Data
public class TslTemplateRequest {

    private String name;

    /** 分类，可空 */
    private String category;

    private String description;

    private Map<String, Object> tsl;
}
