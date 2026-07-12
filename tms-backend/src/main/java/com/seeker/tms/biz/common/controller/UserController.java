package com.seeker.tms.biz.common.controller;

import com.seeker.tms.biz.common.auth.AuthProviderRegistry;
import com.seeker.tms.biz.common.entities.LoginDTO;
import com.seeker.tms.biz.common.entities.LoginVO;
import com.seeker.tms.biz.common.service.UserService;
import com.seeker.tms.common.auth.TokenService;
import com.seeker.tms.common.auth.UserContext;
import com.seeker.tms.common.utils.Result;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.AllArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

@Api(tags = "用户管理")
@RestController
@RequestMapping("/user")
@AllArgsConstructor
public class UserController {

    private final AuthProviderRegistry authProviderRegistry;
    private final UserService userService;
    private final TokenService tokenService;

    @ApiOperation("获取三方登录授权 URL")
    @GetMapping("/{channel}/authorize-url")
    public Result<String> authorizeUrl(@PathVariable String channel) {
        return Result.success(authProviderRegistry.get(channel).buildAuthorizeUrl());
    }

    @ApiOperation("三方登录")
    @PostMapping("/{channel}/login")
    public Result<LoginVO> login(@PathVariable String channel, @Validated @RequestBody LoginDTO dto) {
        return Result.success(authProviderRegistry.get(channel).login(dto.getCode(), dto.getState()));
    }

    @ApiOperation("获取当前登录用户")
    @GetMapping("/me")
    public Result<LoginVO> me() {
        return Result.success(userService.currentUser(UserContext.get()));
    }

    @ApiOperation("登出")
    @PostMapping("/logout")
    public Result<Boolean> logout(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            tokenService.revoke(header.substring(7).trim());
        }
        return Result.success(true);
    }
}
