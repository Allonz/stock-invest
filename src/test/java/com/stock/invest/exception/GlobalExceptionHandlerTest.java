package com.stock.invest.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 全局异常处理器契约：异步超时（SSE 长连接被重启切断）必须返回 503 空响应体，
 * 而不是返回 JSON 对象体——SSE 上下文 Content-Type 为 text/event-stream，
 * 无转换器可写 JSON 对象，会让 @ExceptionHandler 自身失败（历史 ERROR 刷屏根因）。
 */
@DisplayName("GlobalExceptionHandler — 异步超时处理")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("AsyncRequestTimeoutException -> 503 且无响应体")
    void handleAsyncTimeout_returns503WithoutBody() {
        ResponseEntity<Void> resp = handler.handleAsyncTimeout(new AsyncRequestTimeoutException());

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        assertNull(resp.getBody());
    }

    @Test
    @DisplayName("ResponseStatusException -> 保留原始状态码，不落入 500")
    void handleResponseStatus_preservesStatus() {
        ResponseEntity<com.stock.invest.enums.dto.ApiResponse<?>> resp =
                handler.handleResponseStatus(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid key"));

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertEquals(false, resp.getBody().success());
        assertEquals("Invalid key", resp.getBody().message());
    }
}
