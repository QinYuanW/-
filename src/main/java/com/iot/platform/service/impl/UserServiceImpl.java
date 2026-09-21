package com.iot.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.iot.platform.common.BizException;
import com.iot.platform.common.ErrorCode;
import com.iot.platform.config.JwtProperties;
import com.iot.platform.dto.LoginVO;
import com.iot.platform.entity.User;
import com.iot.platform.mapper.UserMapper;
import com.iot.platform.service.UserService;
import com.iot.platform.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final JwtProperties jwtProperties;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public LoginVO login(String username, String password, Boolean rememberMe) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null || (user.getStatus() != null && user.getStatus() != 1)) {
            throw new BizException(ErrorCode.AUTH_FAILED, "用户名或密码错误");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BizException(ErrorCode.AUTH_FAILED, "用户名或密码错误");
        }
        boolean remember = Boolean.TRUE.equals(rememberMe);
        long ttl = remember ? jwtProperties.getRememberExpire() : jwtProperties.getExpire();
        String token = jwtUtil.generate(username, user.getId(), user.getRole(), ttl);
        log.info("user login: {}（{}，有效期 {}s）", username, remember ? "记住我" : "常规", ttl);
        return new LoginVO(token, username, user.getRole());
    }

    @Override
    public void changePassword(String username, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 6) {
            throw new BizException(ErrorCode.PARAM_ERROR, "新密码至少 6 位");
        }
        if (newPassword.equals(oldPassword)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "新密码不能与旧密码相同");
        }
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "用户不存在");
        }
        // 校验旧密码：这是本接口唯一一道确认（票据无状态，无法靠会话二次验证）
        //
        // ⚠️ 这里**不能用 AUTH_FAILED(4010)**：前端把 4010 定义为「会话失效」，
        // 会清 token 并跳登录页 —— 于是"原密码输错"会把用户**直接踢出整个应用**
        // （端到端实测复现：弹窗连同整个布局一起消失、跳回登录页）。
        // **4010 只留给"会话/凭证失效"；用户输入类失败一律用 PARAM_ERROR。**
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "原密码不正确");
        }
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, user.getId())
                .set(User::getPasswordHash, passwordEncoder.encode(newPassword))
                .set(User::getUpdatedAt, LocalDateTime.now()));
        // 日志里不写任何密码内容（含新旧）
        log.info("user password changed: {}", username);
    }
}
