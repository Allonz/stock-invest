package com.stock.invest.exception;

import com.stock.invest.enums.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理资源不存在（404）——旧路径、未映射的端点
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<?>> handleNoResourceFound(NoResourceFoundException e) {
        String path = e.getResourcePath();
        // 浏览器的 favicon.ico 请求、/robots.txt 等静默跳过，不打日志
        if ("favicon.ico".equals(path) || "robots.txt".equals(path)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        log.warn("[404] 资源不存在: method={}, path={}", e.getHttpMethod(), path);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("资源不存在: " + path, "NotFound"));
    }

    /**
     * 处理其他所有未捕获异常（500）
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleGenericException(Exception e) {
        log.error("未处理的异常: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("服务器内部错误，请稍后重试", "InternalServerError"));
    }
}
