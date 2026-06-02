package com.seeker.tms.common.utils;

/**
 * 当前后端尚未引入真实 JWT，前端 token 形如 "user_{username}"。
 * 解析逻辑集中在此，未来切换真实鉴权时只需替换实现。
 */
public class TokenUtil {

    private static final String TOKEN_PREFIX = "user_";

    private TokenUtil() {}

    public static String parseUsername(String token) {
        if (token == null || token.isBlank()) return null;
        if (!token.startsWith(TOKEN_PREFIX)) return null;
        String username = token.substring(TOKEN_PREFIX.length()).trim();
        return username.isEmpty() ? null : username;
    }
}
