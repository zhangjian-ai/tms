package com.seeker.tms.common.feishu;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 飞书用户身份信息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FeishuUserInfo {
    private String openId;
    private String unionId;
    private String name;
    private String avatar;
}
