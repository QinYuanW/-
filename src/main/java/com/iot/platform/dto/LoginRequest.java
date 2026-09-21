package com.iot.platform.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

    /**
     * 「记住我」（评审 2.6）：勾选则签发 7 天有效期的 token，否则仍是默认的 2 小时。
     * 字段可缺省（老的前端调用不受影响），缺省视为 false。
     */
    @JsonProperty("remember_me")
    private Boolean rememberMe;
}
