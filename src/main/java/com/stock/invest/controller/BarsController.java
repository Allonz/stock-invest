package com.stock.invest.controller;

import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.repository.StockDailyBarRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * K线数据查询接口
 */
@RestController
@RequestMapping("/api/bars")
public class BarsController {

    private final StockDailyBarRepository stockDailyBarRepository;

    public BarsController(StockDailyBarRepository stockDailyBarRepository) {
        this.stockDailyBarRepository = stockDailyBarRepository;
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
}
