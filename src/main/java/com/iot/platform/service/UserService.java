package com.iot.platform.service;

import com.iot.platform.dto.LoginVO;

public interface UserService {

    LoginVO login(String username, String password);
}
