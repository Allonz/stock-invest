package com.stock.invest.controller;

import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.enums.dto.ApiResponse;
import com.stock.invest.enums.dto.StockDailyBarCandleDto;
import com.stock.invest.repository.StockDailyBarRepository;
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

    private final StockDailyBarRepository stockDailyBarRepository;
    private final StockDailyBarService stockDailyBarService;

    public BarsController(StockDailyBarRepository stockDailyBarRepository,
                          StockDailyBarService stockDailyBarService) {
        this.stockDailyBarRepository = stockDailyBarRepository;
        this.stockDailyBarService = stockDailyBarService;
    }

    /**
     * 按股票代码查询K线数据
     * GET /api/bars/single/query?symbol=TOVX
     */
    @GetMapping("/single/query")
    public ResponseEntity<Map<String, Object>> getBars(@RequestParam String symbol) {
        String code = symbol.trim().toUpperCase();
        List<StockDailyBar> bars = stockDailyBarRepository
                .findBySymbolOrderByTradeDateDesc(code, PageRequest.of(0, 500));

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

        Sort sort = sortDir.equalsIgnoreCase("desc")
                ? Sort.by(sortBy).descending()
                : Sort.by(sortBy).ascending();

        String sym = (symbol != null && !symbol.isBlank()) ? symbol.trim().toUpperCase() : null;
        LocalDate date = (tradeDate != null && !tradeDate.isBlank()) ? LocalDate.parse(tradeDate) : null;
        String src = (source != null && !source.isBlank()) ? source : null;

        Pageable pageable = PageRequest.of(page, pageSize, sort);
        Page<StockDailyBar> barPage = stockDailyBarRepository.findFiltered(sym, date, src, pageable);

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
        List<String> sources = stockDailyBarRepository.findAllSources();
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
            List<StockDailyBarCandleDto> candles = stockDailyBarService.getRecentCandles(symbol, days);
            return ResponseEntity.ok(ApiResponse.ok(candles));
        } catch (Exception e) {
            log.error("getCandles failed symbol={}", symbol, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve candle data"));
        }
    }
}
