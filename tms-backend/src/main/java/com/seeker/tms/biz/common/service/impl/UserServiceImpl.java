package com.seeker.tms.biz.common.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.seeker.tms.biz.common.entities.LoginVO;
import com.seeker.tms.biz.common.entities.UserPO;
import com.seeker.tms.biz.common.mapper.UserMapper;
import com.seeker.tms.biz.common.service.UserService;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, UserPO> implements UserService {

    @Override
    public LoginVO currentUser(String username) {
        UserPO user = getOne(Wrappers.<UserPO>lambdaQuery().eq(UserPO::getUsername, username));
        if (user == null) {
            throw new IllegalStateException("用户不存在");
        }
        return new LoginVO(null, user.getUsername(), user.getAvatar());
    }
}
