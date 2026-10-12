package com.iot.platform.controller;

import com.iot.platform.common.BizException;
import com.iot.platform.common.ErrorCode;
import com.iot.platform.common.Result;
import com.iot.platform.dto.LoginRequest;
import com.iot.platform.dto.LoginVO;
import com.iot.platform.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/login")
    public Result<LoginVO> login(@RequestBody @Valid LoginRequest request) {
        return Result.ok(userService.login(request.getUsername(), request.getPassword(),
                request.getRememberMe()));
    }

    /**
     * 修改密码（评审 1.4）。
     *
     * <p>用户名**从 token 取**（{@link com.iot.platform.interceptor.JwtInterceptor} 已放进
     * request attribute），不从请求体传 —— 否则任何登录用户都能改别人的密码。
     */
    @PutMapping("/users/password")
    public Result<Void> changePassword(@RequestBody Map<String, Object> body,
                                       HttpServletRequest request) {
        String username = (String) request.getAttribute("username");
        if (username == null) {
            throw new BizException(ErrorCode.AUTH_FAILED, "登录状态无效，请重新登录");
        }
        Object oldPwd = body.get("old_password");
        Object newPwd = body.get("new_password");
        if (oldPwd == null || newPwd == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "缺少 old_password / new_password");
        }
        userService.changePassword(username, String.valueOf(oldPwd), String.valueOf(newPwd));
        return Result.ok();
    }
}
