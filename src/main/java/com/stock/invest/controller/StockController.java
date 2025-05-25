package com.stock.invest.controller;

import com.stock.invest.model.KLineData;
import com.stock.invest.service.StockService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 老虎证券股票数据控制器
 */
@RestController
@RequestMapping("/api/tiger")
public class StockController {

    private static final Logger logger = LoggerFactory.getLogger(StockController.class);
    private static final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StockService stockService;

    @Autowired
    public StockController(StockService stockService) {
        logger.info("StockController " + LocalDateTime.now().format(dateFormat) + ": Controller initialized");
        this.stockService = stockService;
    }

    /**
     * 获取苹果公司的日K线数据
     * @return 苹果公司的日K线数据（JSON格式）
     */
    @GetMapping("/kline/aapl")
    public ResponseEntity<String> getAppleDailyKLineData() {
        logger.info("StockController " + LocalDateTime.now().format(dateFormat) + ": Getting AAPL daily K-line data");
        String klineData = stockService.getDailyKLineData("AAPL");
        return ResponseEntity.ok(klineData);
    }

    /**
     * 获取指定股票的日K线数据
     * @param symbol 股票代码，例如AAPL
     * @return 该股票的日K线数据（JSON格式）
     */
    @GetMapping("/kline/daily/{symbol}")
    public ResponseEntity<String> getDailyKLineData(@PathVariable String symbol) {
        logger.info("StockController " + LocalDateTime.now().format(dateFormat) + ": Getting daily K-line data for symbol: " + symbol);
        String klineData = stockService.getDailyKLineData(symbol);
        return ResponseEntity.ok(klineData);
    }
    
    /**
     * 获取指定股票的日K线数据，返回对象格式
     * @param symbol 股票代码，例如AAPL
     * @return 该股票的日K线数据对象
     */
    @GetMapping("/kline/daily/object/{symbol}")
    public ResponseEntity<KLineData> getDailyKLineDataObject(@PathVariable String symbol) {
        logger.info("StockController " + LocalDateTime.now().format(dateFormat) + ": Getting daily K-line data object for symbol: " + symbol);
        KLineData klineData = stockService.getDailyKLineDataAsObject(symbol);
        return ResponseEntity.ok(klineData);
    }
    
    /**
     * 查询指定股票的日K线数据，可在URL中可以指定时间范围和分页信息
     * @param symbol 股票代码
     * @param beginDate 开始日期 (yyyy-MM-dd格式)
     * @param endDate 结束日期 (yyyy-MM-dd格式)
     * @param limit 每页记录数量
     * @param pageToken 分页标记
     * @return 股票的日K线数据
     */
    @GetMapping("/kline/daily/query")
    public ResponseEntity<String> queryDailyKLineData(
            @RequestParam String symbol,
            @RequestParam(required = false) String beginDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false, defaultValue = "20") Integer limit,
            @RequestParam(required = false, defaultValue = "") String pageToken) {
        
        logger.info("StockController " + LocalDateTime.now().format(dateFormat) + ": Query daily K-line data - symbol: {}, beginDate: {}, endDate: {}, limit: {}, pageToken: {}", 
                    symbol, beginDate, endDate, limit, pageToken);
        
        // 使用简单的日K线数据接口，暂不处理分页
        // 实际实现中应该使用带日期范围和分页的K线接口
        String klineData = stockService.getDailyKLineData(symbol);
        return ResponseEntity.ok(klineData);
    }

    /**
     * 扫描低价股票并进行交易模式筛选
     * 筛选价格在$0.05-0.2之间，并且满足特定的交易量模式
     * 交易量模式: 前一天的平均交易量大于再前一天交易量
     *           前天的平均交易量大于再前天的平均交易量
     *           以此类推，直到最近的平均交易量大于前一天的平均交易量
     * 
     * @param limit 限制结果数量，默认为20
     * @return 符合条件的股票及其K线数据
     */
    @GetMapping("/scan/low-price-volume-pattern")
    public Map<String, Object> scanLowPriceStocksWithVolumePattern(
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        logger.info("StockController " + LocalDateTime.now().format(dateFormat) + 
            ": Scanning low price stocks with volume pattern, limit: " + limit);
        return stockService.scanLowPriceStocksWithVolumePattern(limit);
    }


    @GetMapping("/scan/low-price-volume-pattern/fixstock")
    public Map<String, Object> scanLowPriceStocksWithVolumePattern() {
        logger.info("StockController " + LocalDateTime.now().format(dateFormat) + 
            ": Scanning low price stocks with volume pattern, limit: fixed 20");
        return stockService.scanLowPriceStocksWithVolumePattern();
    }
   
} 