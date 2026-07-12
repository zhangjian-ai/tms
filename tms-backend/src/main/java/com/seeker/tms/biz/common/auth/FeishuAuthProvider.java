package com.seeker.tms.biz.common.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.seeker.tms.biz.common.entities.LoginVO;
import com.seeker.tms.biz.common.entities.UserFeishuTokenPO;
import com.seeker.tms.biz.common.entities.UserPO;
import com.seeker.tms.biz.common.mapper.UserFeishuTokenMapper;
import com.seeker.tms.biz.common.service.UserService;
import com.seeker.tms.common.auth.TokenService;
import com.seeker.tms.common.feishu.FeishuAuthClient;
import com.seeker.tms.common.feishu.FeishuToken;
import com.seeker.tms.common.feishu.FeishuUserInfo;
import com.seeker.tms.common.feishu.UserFeishuTokenProvider;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@AllArgsConstructor
public class FeishuAuthProvider implements AuthProvider, UserFeishuTokenProvider {

    private static final String STATE_KEY_PREFIX = "feishu:oauth_state:";
    private static final long STATE_TTL_MINUTES = 5;
    /** access_token 视为过期的安全缓冲（秒） */
    private static final long ACCESS_BUFFER_SECONDS = 120;
    private static final String CHANNEL_FEISHU = "FEISHU";

    private final FeishuAuthClient feishuAuthClient;
    private final UserService userService;
    private final UserFeishuTokenMapper feishuTokenMapper;
    private final TokenService tokenService;
    private final StringRedisTemplate redisTemplate;

    @Override
    public String channel() {
        return CHANNEL_FEISHU;
    }

    @Override
    public String buildAuthorizeUrl() {
        String state = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(STATE_KEY_PREFIX + state, "1", STATE_TTL_MINUTES, TimeUnit.MINUTES);
        return feishuAuthClient.buildAuthorizeUrl(state);
    }

    @Override
    @Transactional
    public LoginVO login(String code, String state) {
        if (state != null && !state.isBlank()) {
            String key = STATE_KEY_PREFIX + state;
            Boolean exists = redisTemplate.hasKey(key);
            if (!Boolean.TRUE.equals(exists)) {
                throw new IllegalArgumentException("登录状态已过期，请重新发起登录");
            }
            redisTemplate.delete(key);
        }

        FeishuToken token = feishuAuthClient.exchangeCodeForToken(code);
        FeishuUserInfo info = feishuAuthClient.getUserInfo(token.getAccessToken());
        if (info.getOpenId() == null || info.getOpenId().isBlank()) {
            throw new IllegalStateException("未能获取到飞书用户身份");
        }

        UserPO user = upsertUser(info);
        saveFeishuToken(user.getId(), token);

        String loginToken = tokenService.issue(user.getUsername());
        return new LoginVO(loginToken, user.getUsername(), user.getAvatar());
    }

    @Override
    public String getUserAccessToken(String username) {
        return getValidUserAccessToken(username);
    }

    private String getValidUserAccessToken(String username) {
        UserPO user = userService.getOne(Wrappers.<UserPO>lambdaQuery().eq(UserPO::getUsername, username));
        if (user == null) {
            throw new IllegalStateException("用户不存在: " + username);
        }
        UserFeishuTokenPO tk = feishuTokenMapper.selectOne(
                Wrappers.<UserFeishuTokenPO>lambdaQuery().eq(UserFeishuTokenPO::getUserId, user.getId()));
        if (tk == null || tk.getAccessToken() == null) {
            throw new IllegalStateException("用户未完成飞书授权，无法访问飞书文档: " + username);
        }
        LocalDateTime now = LocalDateTime.now();
        boolean accessValid = tk.getAccessExpireAt() != null && tk.getAccessExpireAt().isAfter(now);
        if (accessValid) {
            return tk.getAccessToken();
        }
        boolean refreshValid = tk.getRefreshToken() != null
                && (tk.getRefreshExpireAt() == null || tk.getRefreshExpireAt().isAfter(now));
        if (!refreshValid) {
            throw new IllegalStateException("飞书授权已过期，请重新登录: " + username);
        }
        FeishuToken refreshed = feishuAuthClient.refreshUserToken(tk.getRefreshToken());
        saveFeishuToken(user.getId(), refreshed);
        return refreshed.getAccessToken();
    }

    private UserPO upsertUser(FeishuUserInfo info) {
        UserPO user = userService.getOne(Wrappers.<UserPO>lambdaQuery()
                .eq(UserPO::getChannel, CHANNEL_FEISHU)
                .eq(UserPO::getOpenId, info.getOpenId()));
        if (user == null) {
            user = new UserPO();
            user.setChannel(CHANNEL_FEISHU);
            user.setOpenId(info.getOpenId());
            user.setUnionId(info.getUnionId());
            user.setAvatar(info.getAvatar());
            user.setUsername(uniqueUsername(info.getName(), info.getOpenId()));
            userService.save(user);
        } else {
            user.setUnionId(info.getUnionId());
            user.setAvatar(info.getAvatar());
            userService.updateById(user);
        }
        return user;
    }

    private String uniqueUsername(String name, String openId) {
        String base = (name != null && !name.isBlank())
                ? name.trim()
                : ("feishu_" + openId.substring(0, Math.min(8, openId.length())));
        String candidate = base;
        int n = 1;
        while (userService.getOne(Wrappers.<UserPO>lambdaQuery().eq(UserPO::getUsername, candidate)) != null) {
            candidate = base + "_" + (n++);
        }
        return candidate;
    }

    private void saveFeishuToken(Integer userId, FeishuToken token) {
        LocalDateTime now = LocalDateTime.now();
        UserFeishuTokenPO po = feishuTokenMapper.selectOne(
                Wrappers.<UserFeishuTokenPO>lambdaQuery().eq(UserFeishuTokenPO::getUserId, userId));
        boolean isNew = po == null;
        if (isNew) {
            po = new UserFeishuTokenPO();
            po.setUserId(userId);
        }
        po.setAccessToken(token.getAccessToken());
        if (token.getRefreshToken() != null) {
            po.setRefreshToken(token.getRefreshToken());
        }
        if (token.getExpiresIn() != null) {
            po.setAccessExpireAt(now.plusSeconds(Math.max(0, token.getExpiresIn() - ACCESS_BUFFER_SECONDS)));
        }
        if (token.getRefreshExpiresIn() != null) {
            po.setRefreshExpireAt(now.plusSeconds(token.getRefreshExpiresIn()));
        }
        po.setUpdateTime(now);
        if (isNew) {
            feishuTokenMapper.insert(po);
        } else {
            feishuTokenMapper.updateById(po);
        }
    }
}
