package com.seeker.tms.biz.common.entities;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户飞书授权 token：每个用户一条（user_id 唯一）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_feishu_token")
public class UserFeishuTokenPO {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private Integer userId;

    /** 飞书 user_access_token */
    private String accessToken;

    /** 飞书 refresh_token */
    private String refreshToken;

    /** access_token 过期时间 */
    private LocalDateTime accessExpireAt;

    /** refresh_token 过期时间 */
    private LocalDateTime refreshExpireAt;

    private LocalDateTime updateTime;
}
