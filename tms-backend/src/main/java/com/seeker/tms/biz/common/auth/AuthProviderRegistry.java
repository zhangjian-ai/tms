package com.seeker.tms.biz.common.auth;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 按 channel 汇总所有 {@link AuthProvider}，供登录入口按渠道路由。
 */
@Component
public class AuthProviderRegistry {

    private final Map<String, AuthProvider> providers = new HashMap<>();

    public AuthProviderRegistry(List<AuthProvider> providerList) {
        for (AuthProvider provider : providerList) {
            providers.put(normalize(provider.channel()), provider);
        }
    }

    public AuthProvider get(String channel) {
        AuthProvider provider = channel == null ? null : providers.get(normalize(channel));
        if (provider == null) {
            throw new IllegalArgumentException("不支持的登录渠道: " + channel);
        }
        return provider;
    }

    private String normalize(String channel) {
        return channel.trim().toUpperCase(Locale.ROOT);
    }
}
