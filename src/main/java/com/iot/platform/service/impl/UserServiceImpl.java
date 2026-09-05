package com.iot.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.iot.platform.common.BizException;
import com.iot.platform.common.ErrorCode;
import com.iot.platform.dto.LoginVO;
import com.iot.platform.entity.User;
import com.iot.platform.mapper.UserMapper;
import com.iot.platform.service.UserService;
import com.iot.platform.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public LoginVO login(String username, String password) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null || (user.getStatus() != null && user.getStatus() != 1)) {
            throw new BizException(ErrorCode.AUTH_FAILED, "用户名或密码错误");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BizException(ErrorCode.AUTH_FAILED, "用户名或密码错误");
        }
        String token = jwtUtil.generate(username, user.getId(), user.getRole());
        log.info("user login: {}", username);
        return new LoginVO(token, username, user.getRole());
    }
}
