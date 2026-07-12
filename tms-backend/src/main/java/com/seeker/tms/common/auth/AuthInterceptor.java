package com.seeker.tms.common.auth;

import com.alibaba.fastjson.JSON;
import com.seeker.tms.common.utils.Result;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;

/**
 * REST 全局登录态拦截器：从 Authorization: Bearer <token>（或 query token）解析当前用户，
 * 写入 {@link UserContext}；未登录/失效返回 401。白名单在 WebMvcAuthConfig 中配置。
 */
@Component
@AllArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final TokenService tokenService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 放行 CORS 预检
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String username = tokenService.resolve(resolveToken(request));
        if (username == null) {
            writeUnauthorized(response);
            return false;
        }
        UserContext.set(username);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        // 兼容 query 传参（与 WebSocket 握手一致）
        return request.getParameter("token");
    }

    private void writeUnauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Result<String> body = Result.builder(HttpStatus.UNAUTHORIZED.value(), "未登录或登录已过期", "UNAUTHORIZED");
        response.getWriter().write(JSON.toJSONString(body));
    }
}
