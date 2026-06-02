package com.stock.invest.controller;

import com.stock.invest.entity.TradingCalendarEntity;
import com.stock.invest.enums.dto.ApiResponse;
import com.stock.invest.model.TradingCalendarResult;
import com.stock.invest.repository.TradingCalendarRepository;
import com.stock.invest.service.TradingCalendarDbService;
import com.stock.invest.service.impl.TradingCalendarFallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 交易日历 API 控制器。
 *
 * 端点：
 * - GET  /api/v1/trading-calendar/is-open           — 查单日是否开盘
 * - POST /api/v1/trading-calendar/fetch-full-year    — 手动触发全年日历查询入库
 * - GET  /api/v1/trading-calendar/list               — 返回整年日历列表
 *
 * is-open 策略：DB 优先 → fallback 链实时查 → 全部不可用时默认 true
 */
@RestController
@RequestMapping("/api/v1/trading-calendar")
public class TradingCalendarController {

    private static final Logger log = LoggerFactory.getLogger(TradingCalendarController.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ZoneId NY_ZONE = ZoneId.of("America/New_York");
    private static final String DEFAULT_MARKET = "US";

    private final TradingCalendarDbService dbService;
    private final TradingCalendarRepository repository;
    private final TradingCalendarFallback fallback;

    public TradingCalendarController(TradingCalendarDbService dbService,
                                     TradingCalendarRepository repository,
                                     TradingCalendarFallback fallback) {
        this.dbService = dbService;
        this.repository = repository;
        this.fallback = fallback;
    }

    /**
     * 查询指定日期是否为交易日。
     *
     * 策略（DB 优先，跨年自动补全，默认 true）：
     * ① 查 trading_calendar 表 → 有记录直接返回
     * ② DB 无记录 → fallback 链实时查（Tiger → TigerOpen → Alpaca）
     * ③ 数据源有结果 → upsert 入库 + 返回
     * ④ 全部不可用 → 默认 true（OpenClaw 截图导入不遗漏）
     */
    @GetMapping("/is-open")
    @Transactional
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
            Boolean isOpen;
            String source;
            String sourceDetail;

            // ① 查 DB
            Optional<TradingCalendarEntity> entity = repository.findByMarketAndTradeDate(market, queryDate);
            if (entity.isPresent()) {
                isOpen = entity.get().getIsOpen();
                source = "db:" + entity.get().getSource();
                sourceDetail = entity.get().getDetail();
                log.debug("[is-open] DB 命中: date={}, isOpen={}", queryDate, isOpen);
            } else {
                // ② DB 无记录 → fallback 链实时查（含跨年日期自动补全）
                log.info("[is-open] DB 无记录, 走 fallback: date={}, market={}", queryDate, market);
                TradingCalendarResult result = fallback.isTradingDay(market, queryDate);
                if (result != null) {
                    isOpen = result.isTradingDay();
                    source = result.getSource();
                    sourceDetail = result.getDetail();
                    // ③ 结果 upsert 入库
                    repository.upsert(market, queryDate, isOpen, source, result.getType(), sourceDetail);
                    log.info("[is-open] fallback 结果已入库: date={}, isOpen={}, source={}", queryDate, isOpen, source);
                } else {
                    // ④ 全部不可用 → 默认 true（宁可重复，不遗漏）
                    log.warn("[is-open] 所有数据源不可用, 默认 true: date={}", queryDate);
                    isOpen = true;
                    source = "default";
                    sourceDetail = "all sources unavailable, default true";
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("date", queryDate.format(DATE_FMT));
            data.put("isOpen", isOpen);
            data.put("exchange", exchange);
            data.put("timezone", "America/New_York");
            data.put("source", source);
            data.put("sourceDetail", sourceDetail);
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
     *
     * @param year   年份，默认当年（美东）
     * @param market 市场代码，默认 US
     */
    @PostMapping("/fetch-full-year")
    public ResponseEntity<ApiResponse<Map<String, Object>>> fetchFullYear(
            @RequestParam(value = "year", required = false) Integer year,
            @RequestParam(value = "market", required = false, defaultValue = DEFAULT_MARKET) String market) {

        try {
            int targetYear = (year != null) ? year : Year.now(NY_ZONE).getValue();
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
     *
     * @param year   年份，默认当年（美东）
     * @param market 市场代码，默认 US
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


    /** 交易所 MIC → 市场代码映射 */
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
