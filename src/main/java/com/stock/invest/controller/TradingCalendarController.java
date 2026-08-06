package com.stock.invest.controller;

import com.stock.invest.entity.TradingCalendarEntity;
import com.stock.invest.enums.dto.ApiResponse;
import com.stock.invest.service.TradingCalendarDbService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 交易日历 API 控制器。
 *
 * 端点：
 * - GET  /api/v1/trading-calendar/is-open           — 查单日是否开盘
 * - POST /api/v1/trading-calendar/fetch-full-year    — 手动触发全年日历查询入库
 * - GET  /api/v1/trading-calendar/list               — 返回整年日历列表
 */
@RestController
@RequestMapping("/api/v1/trading-calendar")
public class TradingCalendarController {

    private static final Logger log = LoggerFactory.getLogger(TradingCalendarController.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ZoneId NY_ZONE = ZoneId.of("America/New_York");
    private static final String DEFAULT_MARKET = "US";

    /** P2-10：全量同步冷却窗口（分钟）—— 手动触发 365 天外部抓取限频，防打空配额 */
    private static final int FULL_YEAR_COOLDOWN_MINUTES = 30;
    /** 最近一次全量同步时间，key = "{market}:{year}" */
    private final Map<String, Long> lastFullYearSyncAt = new ConcurrentHashMap<>();

    private final TradingCalendarDbService dbService;

    public TradingCalendarController(TradingCalendarDbService dbService) {
        this.dbService = dbService;
    }

    /**
     * 查询指定日期是否为交易日。
     * 委托 TradingCalendarDbService.isTradingDay() 执行 DB 优先 → fallback 链 → 入库 策略。
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
            Boolean isOpen = dbService.isTradingDay(market, queryDate);

            // 数据库和 fallback 链都不可用 → 默认 true（宁可重复不遗漏）
            if (isOpen == null) {
                log.warn("[is-open] 所有数据源不可用, 默认 true: date={}", queryDate);
                isOpen = true;
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("date", queryDate.format(DATE_FMT));
            data.put("isOpen", isOpen);
            data.put("exchange", exchange);
            data.put("timezone", "America/New_York");
            data.put("source", "dbService");
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
     * 手动触发全年日历查询入库。
     * <p>P2-10：year 缺省当年、范围限制 [当前年-1, 当前年+1]；同一市场同一年份
     * 在冷却窗口（30 分钟）内重复触发返回 429，防止高频调用打空外部数据源配额。</p>
     */
    @PostMapping("/fetch-full-year")
    public ResponseEntity<ApiResponse<Map<String, Object>>> fetchFullYear(
            @RequestParam(value = "year", required = false) Integer year,
            @RequestParam(value = "market", required = false, defaultValue = DEFAULT_MARKET) String market) {

        try {
            int currentYear = Year.now(NY_ZONE).getValue();
            int targetYear = (year != null) ? year : currentYear;

            // P2-10：年份范围校验 —— 只允许同步相邻年份，非法返回 400
            if (targetYear < currentYear - 1 || targetYear > currentYear + 1) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("year 超出允许范围 [" + (currentYear - 1) + ", " + (currentYear + 1) + "]",
                                "INVALID_YEAR"));
            }

            // P2-10：频控 —— 同一 (market, year) 冷却窗口内重复触发直接拒绝
            String cooldownKey = market + ":" + targetYear;
            long now = System.currentTimeMillis();
            Long lastSync = lastFullYearSyncAt.get(cooldownKey);
            if (lastSync != null && now - lastSync < FULL_YEAR_COOLDOWN_MINUTES * 60_000L) {
                long remainingSec = (FULL_YEAR_COOLDOWN_MINUTES * 60_000L - (now - lastSync)) / 1000;
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .body(ApiResponse.error("该年份全量同步进行中/刚完成，请 " + remainingSec + " 秒后重试",
                                "SYNC_IN_PROGRESS"));
            }
            lastFullYearSyncAt.put(cooldownKey, now);

            log.info("[TradingCalendarController] fetchFullYear: market={}, year={}", market, targetYear);

            int fetched = dbService.fetchAndStoreFullYear(market, targetYear);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("fetched", fetched);
            data.put("market", market);
            data.put("year", targetYear);

            return ResponseEntity.ok(ApiResponse.ok(data));

        } catch (Exception e) {
            log.error("[TradingCalendarController] fetchFullYear failed", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Fetch failed: " + e.getMessage()));
        }
    }

    /**
     * 获取整年日历列表。
     */
    @GetMapping("/list")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(
            @RequestParam(value = "year", required = false) Integer year,
            @RequestParam(value = "market", required = false, defaultValue = DEFAULT_MARKET) String market) {

        try {
            int targetYear = (year != null) ? year : Year.now(NY_ZONE).getValue();
            List<TradingCalendarEntity> entities = dbService.getYearCalendar(market, targetYear);

            List<Map<String, Object>> result = new ArrayList<>();
            for (TradingCalendarEntity entity : entities) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("tradeDate", entity.getTradeDate().format(DATE_FMT));
                item.put("isOpen", entity.getIsOpen());
                item.put("market", entity.getMarket());
                item.put("source", entity.getSource());
                item.put("type", entity.getType());
                item.put("detail", entity.getDetail());
                result.add(item);
            }

            return ResponseEntity.ok(ApiResponse.ok(result));

        } catch (Exception e) {
            log.error("[TradingCalendarController] list failed", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("List failed: " + e.getMessage()));
        }
    }

    private static String resolveMarket(String exchange) {
        if (exchange == null) return DEFAULT_MARKET;
        String upper = exchange.toUpperCase();
        if (upper.startsWith("XNYS") || upper.startsWith("XNAS")
                || upper.equals("US") || upper.startsWith("ARCX")) {
            return DEFAULT_MARKET;
        }
        if (upper.startsWith("XHKG") || upper.equals("HK")) {
            return "HK";
        }
        if (upper.startsWith("XSHG") || upper.startsWith("XSHE")
                || upper.equals("CN")) {
            return "CN";
        }
        return DEFAULT_MARKET;
    }
}
