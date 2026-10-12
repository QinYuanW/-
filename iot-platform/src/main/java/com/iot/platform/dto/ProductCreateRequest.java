package com.iot.platform.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

/** E5 新建产品请求 */
@Data
public class ProductCreateRequest {

    /** 产品标识；留空自动生成 p_xxxxxxxx */
    @JsonProperty("product_key")
    private String productKey;

    private String name;

    /** 可选；MVP 单演示项目，缺省挂到 project_id=1 */
    @JsonProperty("project_id")
    private Long projectId;

    /** 可选；不传则初始化为空物模型 {properties:[], services:[], events:[]} */
    private Map<String, Object> tsl;
}
