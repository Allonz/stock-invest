package com.stock.invest.controller;

import com.stock.invest.enums.dto.ApiResponse;
import com.stock.invest.enums.dto.StockDailyBarCandleDto;
import com.stock.invest.enums.dto.StockDailyBarDto;
import com.stock.invest.service.StockDailyBarService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * K线数据查询接口
 */
@RestController
@RequestMapping("/api/bars")
public class BarsController {

    private static final Logger log = LoggerFactory.getLogger(BarsController.class);

    private final StockDailyBarService stockDailyBarService;

    public BarsController(StockDailyBarService stockDailyBarService) {
        this.stockDailyBarService = stockDailyBarService;
    }

    /**
     * 按股票代码查询K线数据
     * GET /api/bars/single/query?symbol=TOVX
     */
    @GetMapping("/single/query")
    public ResponseEntity<Map<String, Object>> getBars(@RequestParam String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        String code = symbol.trim().toUpperCase();
        List<StockDailyBarDto> bars = stockDailyBarService.getBarsBySymbol(code);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", code);
        result.put("total", bars.size());
        result.put("rows", bars);
        return ResponseEntity.ok(result);
    }

    /**
     * 分页查询全量K线数据（支持按股票代码/交易日/数据源筛选）
     * GET /api/bars/pages/query?page=0&pageSize=20&sortBy=tradeDate&sortDir=desc&symbol=AAPL&tradeDate=2026-06-01&source=yfinance
     */
    @GetMapping("/pages/query")
    public ResponseEntity<Map<String, Object>> queryBars(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "tradeDate") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String tradeDate,
            @RequestParam(required = false) String source) {

        // P2-9：sortBy 白名单，非法字段回退 tradeDate，避免 Sort.by 反射异常 500
        String sortField = switch (sortBy == null ? "" : sortBy) {
            case "symbol", "tradeDate", "source", "closePrice", "volume", "id",
                 "openPrice", "highPrice", "lowPrice", "changePercent", "afterHours",
                 "afterHoursChangePercent" -> sortBy;
            default -> "tradeDate";
        };
        Sort sort = sortDir.equalsIgnoreCase("desc")
                ? Sort.by(sortField).descending()
                : Sort.by(sortField).ascending();

        String sym = (symbol != null && !symbol.isBlank()) ? symbol.trim().toUpperCase() : null;
        // P2-9：非法日期交给全局 handler 返回 400（不再裸 LocalDate.parse 抛 500）
        LocalDate date = (tradeDate != null && !tradeDate.isBlank()) ? LocalDate.parse(tradeDate) : null;
        String src = (source != null && !source.isBlank()) ? source : null;

        // P2-9：分页边界 clamp —— page>=0，pageSize∈[1,500]，防止超大分页拖垮 DB
        int safePage = Math.max(0, page);
        int safePageSize = Math.min(Math.max(1, pageSize), 500);

        Pageable pageable = PageRequest.of(safePage, safePageSize, sort);
        Page<StockDailyBarDto> barPage = stockDailyBarService.queryBars(pageable, sym, date, src);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", barPage.getTotalElements());
        result.put("totalPages", barPage.getTotalPages());
        result.put("page", barPage.getNumber());
        result.put("pageSize", barPage.getSize());
        result.put("rows", barPage.getContent());
        return ResponseEntity.ok(result);
    }

    /**
     * 获取所有数据源列表
     * GET /api/bars/sources
     */
    @GetMapping("/sources")
    public ResponseEntity<Map<String, Object>> getSources() {
        List<String> sources = stockDailyBarService.getAllSources();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sources", sources);
        return ResponseEntity.ok(result);
    }

    /**
     * 获取蜡烛图数据（K线）
     * GET /api/bars/{symbol}/candles?days=7
     */
    @GetMapping("/{symbol}/candles")
    public ResponseEntity<ApiResponse<List<StockDailyBarCandleDto>>> getCandles(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "7") int days) {
        try {
            // P2-9：days 边界 clamp [1,365]
            int safeDays = Math.min(Math.max(1, days), 365);
            List<StockDailyBarCandleDto> candles = stockDailyBarService.getRecentCandles(symbol, safeDays);
            return ResponseEntity.ok(ApiResponse.ok(candles));
        } catch (Exception e) {
            log.error("getCandles failed symbol={}", symbol, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve candle data"));
        }
    }
}
