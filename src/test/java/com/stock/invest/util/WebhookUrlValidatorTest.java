package com.stock.invest.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("WebhookUrlValidator — SSRF 白名单校验")
class WebhookUrlValidatorTest {

    private final WebhookUrlValidator validator = new WebhookUrlValidator(
            "localhost,127.0.0.1,::1",
            "http://localhost:8644/webhooks/tiger-orch"
    );

    @Test
    @DisplayName("允许 localhost http")
    void allowLocalhostHttp() {
        assertDoesNotThrow(() -> validator.validate("http://localhost:8645/webhooks/tiger-orch"));
    }

    @Test
    @DisplayName("允许 127.0.0.1 https")
    void allowLoopbackHttps() {
        assertDoesNotThrow(() -> validator.validate("https://127.0.0.1/webhook"));
    }

    @Test
    @DisplayName("允许 IPv6 ::1")
    void allowIpv6Loopback() {
        assertDoesNotThrow(() -> validator.validate("http://[::1]:8645/webhook"));
    }

    @Test
    @DisplayName("拒绝非 http/https 协议")
    void rejectNonHttpScheme() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("ftp://localhost/webhook"));
    }

    @Test
    @DisplayName("拒绝白名单外 host")
    void rejectHostNotInWhitelist() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("http://evil.example.com/webhook"));
    }

    @Test
    @DisplayName("拒绝含用户信息的 URL")
    void rejectUserInfo() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("http://user:pass@localhost/webhook"));
    }

    @Test
    @DisplayName("空 URL 放行（由后续默认值处理）")
    void allowBlank() {
        assertDoesNotThrow(() -> validator.validate(""));
        assertDoesNotThrow(() -> validator.validate(null));
    }
}
