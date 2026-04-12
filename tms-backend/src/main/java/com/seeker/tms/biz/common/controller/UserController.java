package com.seeker.tms.biz.common.controller;

import com.seeker.tms.biz.common.entities.UserDTO;
import com.seeker.tms.biz.common.entities.UserPO;
import com.seeker.tms.biz.common.service.UserService;
import com.seeker.tms.common.utils.Result;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Api(tags = "用户管理")
@RestController
@RequestMapping("/user")
@AllArgsConstructor
public class UserController {

    private final UserService userService;

    @ApiOperation("用户注册")
    @PostMapping("/user/signup")
    public Result<UserPO> signup(@RequestBody UserDTO userDTO){
        UserPO userPO = userService.signup(userDTO);
        return Result.success(userPO);
    }

    @ApiOperation("用户登录")
    @PostMapping("/user/login")
    public Result<Boolean> login(@RequestBody UserDTO userDTO){
        Boolean result = userService.login(userDTO);
        return result ? Result.success(result) : Result.fail(result);
    }
}
