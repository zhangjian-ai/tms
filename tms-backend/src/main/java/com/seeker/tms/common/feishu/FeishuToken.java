package com.seeker.tms.common.feishu;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 飞书用户授权 token（oidc/access_token 与 refresh 的统一返回）。
 * expiresIn / refreshExpiresIn 单位为秒。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FeishuToken {
    private String accessToken;
    private String refreshToken;
    private Integer expiresIn;
    private Integer refreshExpiresIn;
}
