package com.seeker.tms.biz.common.entities;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("user")
public class UserPO {

    @TableId(type = IdType.AUTO)
    private Integer id;

    /** 用户名（即显示名，取自三方登录的昵称） */
    private String username;

    /** 登录渠道，用于区分 feishu / 其他三方，并据此定位对应的 token 表 */
    private String channel;

    /** 三方账号唯一标识（飞书为 open_id） */
    private String openId;

    /** 飞书 union_id */
    private String unionId;

    /** 头像URL */
    private String avatar;
}
