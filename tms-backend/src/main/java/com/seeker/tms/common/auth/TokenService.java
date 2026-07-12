package com.seeker.tms.common.auth;

import com.seeker.tms.common.utils.TokenUtil;
import lombok.AllArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.UUID;

/**
 * 登录态 token 服务：基于 Redis 的不透明 token。
 * key 形如 login_token:{token} -> username，命中即滑动续期。
 * 采用 StringRedisTemplate（纯字符串读写），避免 JDK 序列化带来的 key 扫描问题。
 */
@Component
@AllArgsConstructor
public class TokenService {

    private static final String KEY_PREFIX = "login_token:";
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;

    /** 将自身注册到静态入口 TokenUtil，兼容非 Spring 管理的调用点（如 WebSocket 握手拦截器） */
    @PostConstruct
    public void init() {
        TokenUtil.bind(this);
    }

    /** 为用户签发一个新的不透明登录 token */
    public String issue(String username) {
        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(KEY_PREFIX + token, username, TTL);
        return token;
    }

    /** 解析 token 得到 username，命中则滑动续期；无效返回 null */
    public String resolve(String token) {
        if (token == null || token.isBlank()) return null;
        String key = KEY_PREFIX + token;
        String username = redisTemplate.opsForValue().get(key);
        if (username == null || username.isBlank()) return null;
        redisTemplate.expire(key, TTL);
        return username;
    }

    /** 注销 token */
    public void revoke(String token) {
        if (token == null || token.isBlank()) return;
        redisTemplate.delete(KEY_PREFIX + token);
    }
}
