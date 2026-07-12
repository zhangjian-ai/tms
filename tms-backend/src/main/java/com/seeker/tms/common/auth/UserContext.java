package com.seeker.tms.common.auth;

/**
 * 当前请求线程的登录用户上下文（username）。
 * 由 {@link AuthInterceptor} 在 preHandle 写入、afterCompletion 清理。
 */
public final class UserContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private UserContext() {}

    public static void set(String username) {
        CURRENT.set(username);
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
