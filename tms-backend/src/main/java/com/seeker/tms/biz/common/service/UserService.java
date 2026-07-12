package com.seeker.tms.biz.common.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.seeker.tms.biz.common.entities.LoginVO;
import com.seeker.tms.biz.common.entities.UserPO;

/**
 * 用户基础服务。登录流程见各 {@link com.seeker.tms.biz.common.auth.AuthProvider}。
 */
public interface UserService extends IService<UserPO> {

    /** 按 username 返回展示信息（前端刷新用） */
    LoginVO currentUser(String username);
}
