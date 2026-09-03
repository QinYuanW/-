package com.iot.platform.controller;

import com.iot.platform.dto.AuthRequest;
import com.iot.platform.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/auth")
    public ResponseEntity<?> authenticate(@RequestBody AuthRequest request) {
        boolean ok = authService.authenticate(request.getUsername(), request.getPassword());
        if (ok) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok("{\"result\":\"deny\"}");
    }
}