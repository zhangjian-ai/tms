package com.seeker.tms.common.utils;

import com.seeker.tms.common.auth.TokenService;

/**
 * 登录 token 解析入口。真实解析委托给 Redis 支持的 {@link TokenService}，
 * 通过静态桥接兼容非 Spring 管理的调用点（如 WebSocket 握手拦截器）。
 */
public class TokenUtil {

    private static TokenService tokenService;

    private TokenUtil() {}

    /** 由 TokenService 在启动时注入自身 */
    public static void bind(TokenService service) {
        tokenService = service;
    }

    public static String parseUsername(String token) {
        if (token == null || token.isBlank()) return null;
        return tokenService != null ? tokenService.resolve(token) : null;
    }
}
