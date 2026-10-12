package com.iot.platform.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.iot.platform.entity.Product;
import lombok.Data;

import java.util.Collections;
import java.util.Map;

/** 产品视图对象（tsl 以对象形式返回，前端可直接渲染/编辑） */
@Data
public class ProductVO {

    private Long id;

    @JsonProperty("product_key")
    private String productKey;

    private String name;

    @JsonProperty("node_type")
    private Integer nodeType;

    private String protocol;

    @JsonProperty("data_format")
    private String dataFormat;

    /** 物模型（已解析为对象；解析失败返回空对象，不阻断列表展示） */
    private Map<String, Object> tsl;

    /** F2 启用状态：1 启用 / 0 停用（停用后其下所有设备拒绝接入） */
    private Integer status;

    @JsonProperty("created_at")
    private String createdAt;

    public static ProductVO from(Product p, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        ProductVO vo = new ProductVO();
        vo.setId(p.getId());
        vo.setProductKey(p.getProductKey());
        vo.setName(p.getName());
        vo.setNodeType(p.getNodeType());
        vo.setProtocol(p.getProtocol());
        vo.setDataFormat(p.getDataFormat());
        vo.setStatus(p.getStatus());
        vo.setCreatedAt(p.getCreatedAt() != null ? p.getCreatedAt().toString() : null);
        vo.setTsl(parseTsl(p.getTsl(), mapper));
        return vo;
    }

    private static Map<String, Object> parseTsl(String json, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return mapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
