package com.seeker.tms.common.feishu;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.seeker.tms.common.config.FeishuProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 飞书开放平台鉴权客户端：应用凭据、OAuth 授权码换 token、刷新、用户信息。
 * 使用 OkHttp + fastjson，与工程既有栈一致。
 *
 * 端点参考飞书开放平台 authen v1；如后续飞书调整以官方文档为准。
 */
@Slf4j
@Component
public class FeishuAuthClient {

    private static final String APP_TOKEN_REDIS_KEY = "feishu:app_access_token";
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    private final FeishuProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final OkHttpClient httpClient;

    public FeishuAuthClient(FeishuProperties properties, StringRedisTemplate redisTemplate) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /** 构造授权 URL，用户浏览器跳转扫码/授权后回调至 redirectUri?code=&state= */
    public String buildAuthorizeUrl(String state) {
        String redirect = URLEncoder.encode(properties.getRedirectUri(), StandardCharsets.UTF_8);
        StringBuilder url = new StringBuilder(properties.getBaseUrl())
                .append("/open-apis/authen/v1/authorize")
                .append("?app_id=").append(properties.getAppId())
                .append("&redirect_uri=").append(redirect)
                .append("&state=").append(state == null ? "" : URLEncoder.encode(state, StandardCharsets.UTF_8));
        // scope 决定换回的 user_access_token 能访问哪些资源；不带则仅默认范围（无法访问 wiki/docx 等）
        String scope = properties.getScope();
        if (scope != null && !scope.isBlank()) {
            url.append("&scope=").append(URLEncoder.encode(scope.trim(), StandardCharsets.UTF_8));
        }
        return url.toString();
    }

    /** 获取并缓存 app_access_token（内部应用）。注意：该接口字段在响应根节点，不在 data 下 */
    public String getAppAccessToken() {
        String cached = redisTemplate.opsForValue().get(APP_TOKEN_REDIS_KEY);
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        JSONObject body = new JSONObject();
        body.put("app_id", properties.getAppId());
        body.put("app_secret", properties.getAppSecret());
        JSONObject resp = post("/open-apis/auth/v3/app_access_token/internal", body, null);
        String token = resp.getString("app_access_token");
        if (token == null || token.isBlank()) {
            throw new RuntimeException("未获取到 app_access_token，请检查 app-id/app-secret");
        }
        Integer expire = resp.getInteger("expire"); // 秒
        long ttl = (expire != null ? expire : 7200) - 300; // 提前 5 分钟刷新
        if (ttl > 0) {
            redisTemplate.opsForValue().set(APP_TOKEN_REDIS_KEY, token, ttl, TimeUnit.SECONDS);
        }
        return token;
    }

    /** 授权码换取 user_access_token */
    public FeishuToken exchangeCodeForToken(String code) {
        JSONObject body = new JSONObject();
        body.put("grant_type", "authorization_code");
        body.put("code", code);
        JSONObject resp = post("/open-apis/authen/v1/oidc/access_token", body, getAppAccessToken());
        return toToken(dataOf(resp));
    }

    /** 用 refresh_token 刷新 user_access_token */
    public FeishuToken refreshUserToken(String refreshToken) {
        JSONObject body = new JSONObject();
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", refreshToken);
        JSONObject resp = post("/open-apis/authen/v1/oidc/refresh_access_token", body, getAppAccessToken());
        return toToken(dataOf(resp));
    }

    /** 获取当前用户信息 */
    public FeishuUserInfo getUserInfo(String userAccessToken) {
        JSONObject data = dataOf(get("/open-apis/authen/v1/user_info", userAccessToken));
        FeishuUserInfo info = new FeishuUserInfo();
        info.setOpenId(data.getString("open_id"));
        info.setUnionId(data.getString("union_id"));
        info.setName(data.getString("name"));
        info.setAvatar(data.getString("avatar_url"));
        return info;
    }

    private JSONObject dataOf(JSONObject resp) {
        JSONObject data = resp.getJSONObject("data");
        return data != null ? data : new JSONObject();
    }

    private FeishuToken toToken(JSONObject data) {
        FeishuToken token = new FeishuToken();
        token.setAccessToken(data.getString("access_token"));
        token.setRefreshToken(data.getString("refresh_token"));
        token.setExpiresIn(data.getInteger("expires_in"));
        token.setRefreshExpiresIn(data.getInteger("refresh_expires_in"));
        return token;
    }

    // ---- HTTP 辅助 ----

    private JSONObject post(String path, JSONObject body, String bearer) {
        Request.Builder builder = new Request.Builder()
                .url(properties.getBaseUrl() + path)
                .post(RequestBody.create(body.toJSONString(), JSON_MEDIA));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return execute(builder.build(), path);
    }

    private JSONObject get(String path, String bearer) {
        Request request = new Request.Builder()
                .url(properties.getBaseUrl() + path)
                .header("Authorization", "Bearer " + bearer)
                .get()
                .build();
        return execute(request, path);
    }

    /** 执行请求并校验飞书返回码，返回完整响应根节点（部分接口字段在根、部分在 data） */
    private JSONObject execute(Request request, String path) {
        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            JSONObject json = JSON.parseObject(respBody);
            if (json == null) {
                throw new RuntimeException("飞书接口返回为空: " + path);
            }
            Integer code = json.getInteger("code");
            if (code == null || code != 0) {
                throw new RuntimeException("飞书接口失败[" + path + "]: " + json.getString("msg") + " (code=" + code + ")");
            }
            return json;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("调用飞书接口异常: " + path, e);
        }
    }
}
