package com.iot.platform.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data  //Lombok：自动生成 getter/setter/toString
@TableName("user")   //指定数据库表名,映射到数据库表 user
public class User {

    @TableId(type = IdType.AUTO)// 主键，自增
    private Long id;

    // 普通字段：驼峰自动映射到下划线列名
    private String username;    // → 列 username
    private String passwordHash; // → 列 password_hash
    private Integer role;
    private Integer status;

    @TableLogic// 逻辑删除字段，值为 1 时表示已删除
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}