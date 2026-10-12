package com.iot.platform.dto;

import lombok.Data;

@Data
public class AuthRequest {
    private String clientid;
    private String username;
    private String password;
}