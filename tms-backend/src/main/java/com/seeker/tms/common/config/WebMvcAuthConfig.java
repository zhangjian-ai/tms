package com.seeker.tms.common.config;

import com.seeker.tms.common.auth.AuthInterceptor;
import lombok.AllArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册全局登录态拦截器：除白名单外所有 REST 接口都要求登录。
 * 路径不含 context-path（/api），即相对 DispatcherServlet 的路径。
 */
@Configuration
@AllArgsConstructor
public class WebMvcAuthConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        // 三方登录相关（授权链接、回调换 token），按渠道路由
                        "/user/*/authorize-url",
                        "/user/*/login",
                        // 框架/文档
                        "/error",
                        "/favicon.ico",
                        "/doc.html",
                        "/webjars/**",
                        "/swagger-resources/**",
                        "/v2/api-docs/**",
                        "/v3/api-docs/**"
                );
    }
}
