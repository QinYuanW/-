package com.iot.platform.controller;

import com.iot.platform.dto.AuthRequest;
import com.iot.platform.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * EMQX 5.x HTTP 认证回调契约：
     * <ul>
     *   <li><b>通过 = 204</b>（无响应体，EMQX 视为 allow）</li>
     *   <li><b>拒绝 = 200 + {@code {"result":"deny"}}</b></li>
     * </ul>
     * <b>拒绝响应必须显式声明 {@code application/json}</b>：EMQX 5.x 会校验响应
     * Content-Type，若为 {@code text/plain}（Spring 对 String 返回值的默认类型），
     * 则被判为 {@code unsupported_content_type} → {@code parse_http_response_failed}，
     * 只能靠 fail-closed 兜底拒绝，且每次拒绝都刷 ERROR 日志。语义上也更脆弱 ——
     * 一旦解析失败被当作 {@code ignore}（继续下一个认证器），就可能误放行。
     */
    @PostMapping("/auth")
    public ResponseEntity<?> authenticate(@RequestBody AuthRequest request) {
        boolean ok = authService.authenticate(request.getUsername(), request.getPassword());
        if (ok) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"result\":\"deny\"}");
    }
}