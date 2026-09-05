package com.iot.platform.controller;

import com.iot.platform.common.Result;
import com.iot.platform.dto.LoginRequest;
import com.iot.platform.dto.LoginVO;
import com.iot.platform.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/login")
    public Result<LoginVO> login(@RequestBody @Valid LoginRequest request) {
        return Result.ok(userService.login(request.getUsername(), request.getPassword()));
    }
}
