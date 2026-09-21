package com.iot.platform.service;

import com.iot.platform.dto.LoginVO;

public interface UserService {

    /**
     * 登录并签发 token。
     *
     * @param rememberMe 「记住我」：true 用更长的有效期（{@code jwt.remember-expire}，默认 7 天），
     *                   否则用默认 2 小时（评审 2.6）
     */
    LoginVO login(String username, String password, Boolean rememberMe);

    /**
     * 修改密码（评审 1.4）。
     *
     * <p>必须校验旧密码：否则任何持有效 token 的人都能直接改掉密码 —— 票据是无状态的，
     * 校验旧密码是这里唯一的一道确认。
     *
     * <p>注意：**已签发的 token 不会因改密码而失效**（JWT 无状态、服务端无法撤销）。
     * 单人本地使用可以接受；将来若要做"改密码即强制下线"，需要引入 token 版本号或黑名单。
     */
    void changePassword(String username, String oldPassword, String newPassword);
}
