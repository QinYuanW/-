package com.iot.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通用物模型模板（用户自定义）。
 *
 * <p><b>为什么是表而不是前端常量</b>：模板分两类，生命周期完全不同 ——
 * <ul>
 *   <li><b>内置模板</b>：随代码版本走的"范文"，放在前端常量里（换库/换环境也一定在）；</li>
 *   <li><b>自定义模板</b>：用户数据，必须随库走。若存浏览器 localStorage，
 *       换浏览器/清缓存就没了，用户会以为"系统把数据弄丢了"（这正是 4.7 拔掉的坑）。</li>
 * </ul>
 * 所以内置仍在前端、自定义落这张表，前端负责合并展示。{@code builtin} 字段是为将来
 * 「把内置模板也入库（作为种子数据）」预留的，当前恒为 0。
 *
 * <p>{@code tsl} 与 {@code product.tsl} <b>同构</b>（properties/events/services），
 * 因此"套用模板"就是一次赋值，"存为模板"就是一次快照 —— 不需要任何格式转换。
 * 校验也复用 {@link com.iot.platform.util.TslValidator}，与产品物模型同一套规则。
 */
@Data
@TableName("tsl_template")
public class TslTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 分类（环境监测 / 控制类 / 安防类 …），可空 —— 用于模板库分组展示 */
    private String category;

    private String description;

    /** 物模型定义（JSON 文本，列类型 json） */
    private String tsl;

    /** 预留：1 = 内置模板入过库。当前恒 0（内置仍在前端常量） */
    private Integer builtin;

    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
