package com.seeker.tms.biz.common.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.seeker.tms.biz.common.entities.UserDTO;
import com.seeker.tms.biz.common.entities.UserPO;
import com.seeker.tms.biz.common.mapper.UserMapper;
import com.seeker.tms.biz.common.service.UserService;
import jdk.nashorn.internal.runtime.regexp.joni.exception.ValueException;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, UserPO> implements UserService  {

    @Override
    public UserPO signup(UserDTO userDTO) {
        UserPO userPO = this.lambdaQuery().eq(UserPO::getUsername, userDTO.getUsername()).one();
        if (userPO != null) {
            throw new ValueException("用户名已存在: " + userDTO.getUsername());
        }

        UserPO userPO1 = new UserPO();
        userPO1.setUsername(userDTO.getUsername());
        userPO1.setPassword(userDTO.getPassword());

        boolean saved = this.save(userPO1);
        if (saved){
            return userPO1;
        }

        throw new RuntimeException("用户新增失败 " + userDTO.getUsername());
    }

    @Override
    public Boolean login(UserDTO userDTO) {
        UserPO userPO = this.lambdaQuery().eq(UserPO::getUsername, userDTO.getUsername())
                .eq(UserPO::getPassword, userDTO.getPassword())
                .one();

        return userPO != null;
    }
}
