package com.stock.invest.controller;

import com.stock.invest.enums.dto.ApiResponse;
import com.stock.invest.model.TradingCalendarResult;
import com.stock.invest.service.impl.TradingCalendarFallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 交易日历 API 控制器。
 * 使用 TradingCalendarFallback 实现多数据源自动切换。
 */
@RestController
@RequestMapping("/api/v1/trading-calendar")
public class TradingCalendarController {

    private static final Logger log = LoggerFactory.getLogger(TradingCalendarController.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ZoneId NY_ZONE = ZoneId.of("America/New_York");

    private final TradingCalendarFallback fallback;

    public TradingCalendarController(TradingCalendarFallback fallback) {
        this.fallback = fallback;
    }

    /**
     * 查询指定日期是否为交易日。
     * 走 fallback 链：Tiger → TigerOpen → Alpaca → 默认 true。
     *
     * @param dateParam 日期，可选，默认今天（美东）
     * @param exchange  交易所（历史兼容，实际取市场代码）
     * @return isOpen + 来源信息
     */
    @GetMapping("/is-open")
    public ResponseEntity<ApiResponse<Map<String, Object>>> isOpen(
            @RequestParam(value = "date", required = false) String dateParam,
            @RequestParam(value = "exchange", required = false, defaultValue = "XNYS") String exchange) {

        try {
            LocalDate queryDate;
            if (dateParam != null && !dateParam.trim().isEmpty()) {
                queryDate = LocalDate.parse(dateParam.trim(), DATE_FMT);
            } else {
                queryDate = LocalDate.now(NY_ZONE);
            }

            String market = resolveMarket(exchange);
            TradingCalendarResult result = fallback.isTradingDay(market, queryDate);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("date", queryDate.format(DATE_FMT));
            data.put("isOpen", result.isTradingDay());
            data.put("exchange", exchange);
            data.put("timezone", "America/New_York");
            data.put("source", result.getSource());
            data.put("sourceDetail", result.getDetail());
            data.put("market", market);

            return ResponseEntity.ok(ApiResponse.ok(data));

        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Invalid date format, expected yyyy-MM-dd", "INVALID_DATE"));
        } catch (Exception e) {
            log.error("[TradingCalendarController] is-open failed", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Internal error: " + e.getMessage()));
        }
    }

    /**
     * 清除日历缓存。
     */
    @PostMapping("/cache/clear")
    public ResponseEntity<ApiResponse<Map<String, Object>>> clearCache() {
        fallback.clearCache();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cleared", true);
        data.put("message", "日历查询缓存已清空");
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    /** 交易所 MIC → 市场代码映射 */
    private static String resolveMarket(String exchange) {
        if (exchange == null) return "US";
        String upper = exchange.toUpperCase();
        if (upper.startsWith("XNYS") || upper.startsWith("XNAS")
                || upper.equals("US") || upper.startsWith("ARCX")) {
            return "US";
        }
        if (upper.startsWith("XHKG") || upper.equals("HK")) {
            return "HK";
        }
        if (upper.startsWith("XSHG") || upper.startsWith("XSHE")
                || upper.equals("CN")) {
            return "CN";
        }
        return "US"; // 默认 US
    }
}
