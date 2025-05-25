package com.stock.invest.controller;

import com.stock.invest.enums.MarketScannerCategory;
import com.stock.invest.service.StockService;
import com.tigerbrokers.stock.openapi.client.struct.enums.Market;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 股票筛选控制器
 * 提供 REST API 接口调用股票筛选功能
 */
@RestController
@RequestMapping("/api/scanner")
public class StockScannerController {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final StockService stockService;

    public StockScannerController(StockService stockService) {
        this.stockService = stockService;
    }


    /**
     * 自定义股票筛选
     * @param params 请求参数，包含：
     *               market - 市场代码，例如 US, HK, CN
     *               category - 筛选类型
     *               limit - 限制结果数量，默认为20
     *               minPrice - 最低价格（可选）
     *               maxPrice - 最高价格（可选）
     * @return 股票列表
     */
    @PostMapping("/custom")
    public ResponseEntity<List<String>> customStockScan(@RequestBody Map<String, Object> params) {
        // 解析参数
        String marketStr = (String) params.getOrDefault("market", "US");
        String categoryStr = (String) params.getOrDefault("category", "TOP_VOLUME");
        Integer limit = (Integer) params.getOrDefault("limit", 20);
        Double minPrice = params.containsKey("minPrice") ? Double.valueOf(params.get("minPrice").toString()) : null;
        Double maxPrice = params.containsKey("maxPrice") ? Double.valueOf(params.get("maxPrice").toString()) : null;

        // 转换枚举
        Market market = Market.valueOf(marketStr);
        MarketScannerCategory category = MarketScannerCategory.valueOf(categoryStr);

        logger.info("StockScannerController " + LocalDateTime.now().format(dateFormat) + 
                   ": Custom stock scan, market: {}, category: {}, limit: {}, minPrice: {}, maxPrice: {}", 
                   market, category, limit, minPrice, maxPrice);

        // 执行筛选
        List<String> stocks = stockService.scanStocks(market, limit, minPrice, maxPrice);
        return ResponseEntity.ok(stocks);
    }
} 