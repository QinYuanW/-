package com.iot.platform.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 通用物模型模板出参。
 *
 * <p>{@code tsl} 直接给出结构化对象（不是字符串）—— 前端拿到即可预览属性/事件/服务表格，
 * 也可以原样交给"套用到产品"，无需任何解析。
 */
@Data
public class TslTemplateVO {

    private Long id;

    private String name;

    private String category;

    private String description;

    private Map<String, Object> tsl;

    /** 1 = 内置模板（当前均为 0；内置仍在控制台前端常量里） */
    private Integer builtin;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
