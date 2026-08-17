package com.stock.invest.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 管理/MCP 接口强制鉴权拦截器。
 *
 * <p>保护范围由 WebConfig 注册：/api/admin/**、/api/orchestration/**、
 * POST /api/blacklist/clear、/api/mcp。
 * 请求头：X-Admin-API-Key。未配置 key 时 fail closed。</p>
 */
public class AdminAuthInterceptor implements org.springframework.web.servlet.HandlerInterceptor {

    public static final String ADMIN_API_KEY_HEADER = "X-Admin-API-Key";

    private final String apiKey;

    public AdminAuthInterceptor(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        verify(request.getHeader(ADMIN_API_KEY_HEADER));
        return true;
    }

    /**
     * 恒定时间比较校验 API Key。未配置服务端 key 时返回 503（fail closed）。
     */
    void verify(String headerValue) {
        if (apiKey.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "STOCK_INVEST_ADMIN_API_KEY is not configured on server");
        }
        if (headerValue == null || headerValue.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or missing X-ADMIN-API-KEY");
        }
        String provided = headerValue.trim();
        if (!MessageDigest.isEqual(
                apiKey.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or missing X-ADMIN-API-KEY");
        }
    }
}
