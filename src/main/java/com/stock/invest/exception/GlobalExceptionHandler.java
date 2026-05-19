package com.stock.invest.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.stock.invest.enums.dto.ApiResponse;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(StockDataException.class)
    public ResponseEntity<ApiResponse<?>> handleStockDataException(StockDataException e) {
        log.error("股票数据异常: {}", e.getMessage(), e);

        String context = "";
        if (e.getSymbol() != null) {
            context += " symbol=" + e.getSymbol();
        }
        if (e.getDataSource() != null) {
            context += " dataSource=" + e.getDataSource();
        }
        String message = e.getMessage() + context;

        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;

        if (e instanceof StockDataException.SymbolNotFoundException) {
            status = HttpStatus.NOT_FOUND;
        } else if (e instanceof StockDataException.DataSourceUnavailableException) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
        }

        return ResponseEntity.status(status).body(ApiResponse.error(message, "StockDataException"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<?>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("参数校验失败: {}", e.getMessage());

        return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage(), "IllegalArgumentException"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleGenericException(Exception e) {
        log.error("未处理的异常: {}", e.getMessage(), e);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("服务器内部错误，请稍后重试", "InternalServerError"));
    }
}
