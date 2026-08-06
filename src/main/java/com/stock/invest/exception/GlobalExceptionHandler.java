package com.stock.invest.exception;

import com.stock.invest.enums.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.format.DateTimeParseException;

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
     * 处理非法参数异常（400）
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<?>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("[400] 非法参数: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(e.getMessage(), "BadRequest"));
    }

    /**
     * 处理请求体解析失败（400）
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<?>> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("[400] 请求体解析失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("请求体格式错误", "BadRequest"));
    }

    /**
     * 处理参数类型不匹配（400）
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<?>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("[400] 参数类型不匹配: param={}, value={}", e.getName(), e.getValue());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("参数类型不匹配: " + e.getName(), "BadRequest"));
    }

    /**
     * 处理非法日期字符串（400）——P2-8：之前 LocalDate.parse 失败落入 500
     */
    @ExceptionHandler(DateTimeParseException.class)
    public ResponseEntity<ApiResponse<?>> handleDateTimeParse(DateTimeParseException e) {
        log.warn("[400] 日期格式非法: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("日期格式非法，应为 yyyy-MM-dd", "BadRequest"));
    }

    /**
     * 处理缺少必填请求参数（400）——P2-8
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<?>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("[400] 缺少必填参数: {}", e.getParameterName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("缺少必填参数: " + e.getParameterName(), "BadRequest"));
    }

    /**
     * 处理 HTTP 方法不支持（405）——P2-8：之前落入 500
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<?>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("[405] 方法不支持: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error("请求方法不支持: " + e.getMethod(), "MethodNotAllowed"));
    }

    /**
     * 处理数据完整性冲突（409）——P2-8：唯一约束冲突等
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<?>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        log.warn("[409] 数据完整性冲突: {}", e.getMostSpecificCause() != null
                ? e.getMostSpecificCause().getMessage() : e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("数据冲突或违反约束", "Conflict"));
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
