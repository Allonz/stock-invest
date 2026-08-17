package com.stock.invest.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("AdminAuthInterceptor — 管理/MCP API Key 校验")
class AdminAuthInterceptorTest {

    @Test
    @DisplayName("服务端未配置 key 时 fail closed 返回 503")
    void unconfiguredKey_throws503() {
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor("");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> interceptor.verify("whatever"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
    }

    @Test
    @DisplayName("缺失 header 返回 401")
    void missingHeader_throws401() {
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor("prod-key");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> interceptor.verify(null));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    @DisplayName("错误 key 返回 401")
    void wrongKey_throws401() {
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor("prod-key");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> interceptor.verify("wrong-key"));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    @DisplayName("正确 key 放行")
    void correctKey_passes() {
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor("prod-key");
        assertDoesNotThrow(() -> interceptor.verify("prod-key"));
    }

    @Test
    @DisplayName("header 前后空格 trim 后比较")
    void headerWithSpaces_isTrimmed() {
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor("prod-key");
        assertDoesNotThrow(() -> interceptor.verify("  prod-key  "));
    }
}
