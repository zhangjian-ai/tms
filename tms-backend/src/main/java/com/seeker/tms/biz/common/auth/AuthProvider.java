package com.seeker.tms.biz.common.auth;

import com.seeker.tms.biz.common.entities.LoginVO;

/**
 * 三方登录渠道抽象。每种登录方式（飞书、企业微信等）实现一个 AuthProvider。
 */
public interface AuthProvider {

    /** 渠道标识，与 UserPO.channel 一致，大小写不敏感 */
    String channel();

    /** 生成带 state 的授权 URL */
    String buildAuthorizeUrl();

    /** 授权码换取登录态：换 token、upsert 用户、签发登录 token */
    LoginVO login(String code, String state);
}
