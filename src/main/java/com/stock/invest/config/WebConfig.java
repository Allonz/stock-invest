package com.stock.invest.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ApiLoggingInterceptor apiLoggingInterceptor;

    @Value("${admin.api-key:}")
    private String adminApiKey;

    public WebConfig(ApiLoggingInterceptor apiLoggingInterceptor) {
        this.apiLoggingInterceptor = apiLoggingInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiLoggingInterceptor)
                .addPathPatterns("/api/**");

        // 管理/编排/黑名单写操作/MCP 端点强制 X-Admin-API-Key 鉴权
        registry.addInterceptor(new AdminAuthInterceptor(adminApiKey))
                .addPathPatterns("/api/admin/**", "/api/orchestration/**", "/api/mcp", "/api/mcp/**")
                .addPathPatterns("/api/blacklist/clear", "/api/v1/trading-calendar/fetch-full-year");
    }
}
