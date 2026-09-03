package com.iot.platform.service;

public interface AuthService {
    boolean authenticate(String deviceKey, String password);
}