package com.stock.invest.security;

import com.stock.invest.config.IngestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("IngestApiGuard — API Key 校验")
class IngestApiGuardTest {

    private IngestApiGuard guardWith(String configuredKey) {
        IngestProperties props = new IngestProperties();
        props.setApiKey(configuredKey);
        return new IngestApiGuard(props);
    }

    @Test
    @DisplayName("未配置 key 时 fail closed 返回 503")
    void noKeyConfigured_failsClosedWith503() {
        IngestApiGuard guard = guardWith("");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> guard.verifyOptionalKey("anything"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
    }

    @Test
    @DisplayName("配置 key 时正确 header 放行")
    void correctKey_passes() {
        IngestApiGuard guard = guardWith("secret-key");
        assertDoesNotThrow(() -> guard.verifyOptionalKey("secret-key"));
    }

    @Test
    @DisplayName("配置 key 时正确 header 带前后空格放行")
    void correctKey_trimsHeaderBeforeCompare() {
        IngestApiGuard guard = guardWith("secret-key");
        assertDoesNotThrow(() -> guard.verifyOptionalKey("  secret-key  "));
    }

    @Test
    @DisplayName("配置 key 时错误 header 返回 401")
    void wrongKey_returns401() {
        IngestApiGuard guard = guardWith("secret-key");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> guard.verifyOptionalKey("wrong-key"));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    @DisplayName("配置 key 时缺失或空白 header 返回 401")
    void missingOrBlankHeader_returns401() {
        IngestApiGuard guard = guardWith("secret-key");
        assertThrows(ResponseStatusException.class, () -> guard.verifyOptionalKey(null));
        assertThrows(ResponseStatusException.class, () -> guard.verifyOptionalKey("   "));
    }
}
