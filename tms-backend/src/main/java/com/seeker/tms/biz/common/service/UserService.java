package com.seeker.tms.biz.common.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.seeker.tms.biz.common.entities.UserDTO;
import com.seeker.tms.biz.common.entities.UserPO;

public interface UserService extends IService<UserPO> {

    UserPO signup(UserDTO userDTO);

    Boolean login(UserDTO userDTO);
}
