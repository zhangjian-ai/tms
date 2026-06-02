package com.seeker.tms.biz.testgen.websocket;

import com.seeker.tms.common.utils.TokenUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

/**
 * 用例生成 WebSocket 握手拦截器：从 URL query 中读取 token，解析出 username 写入 attributes。
 * token 缺失或非法直接拒绝握手。
 */
@Slf4j
public class TestGenAuthHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USERNAME = "username";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = extractQueryParam(request.getURI(), "token");
        String username = TokenUtil.parseUsername(token);
        if (username == null) {
            log.warn("拒绝 WebSocket 握手：token 缺失或非法，uri={}", request.getURI());
            return false;
        }
        attributes.put(ATTR_USERNAME, username);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    private String extractQueryParam(URI uri, String key) {
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) return null;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx <= 0) continue;
            if (key.equals(pair.substring(0, idx))) {
                return java.net.URLDecoder.decode(pair.substring(idx + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
