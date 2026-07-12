package com.seeker.tms.common.feishu;

/**
 * 按用户提供有效的飞书 user_access_token（临期自动刷新）。
 * 由业务层（用户/授权服务）实现，供 common 层的文档抓取器解耦调用。
 */
public interface UserFeishuTokenProvider {

    String getUserAccessToken(String username);
}
