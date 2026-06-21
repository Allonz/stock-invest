package com.stock.invest.service.impl;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
import com.stock.invest.model.StockInfo;
import com.stock.invest.service.DataSourceStrategy;
import com.stock.invest.service.PatternEvaluateService;
import com.tigerbrokers.stock.openapi.client.https.client.TigerHttpClient;
import com.tigerbrokers.stock.openapi.client.https.domain.quote.item.KlineItem;
import com.tigerbrokers.stock.openapi.client.https.domain.quote.item.KlinePoint;
import com.tigerbrokers.stock.openapi.client.https.domain.quote.model.BaseFilter;
import com.tigerbrokers.stock.openapi.client.https.request.quote.MarketScannerRequest;
import com.tigerbrokers.stock.openapi.client.https.request.quote.QuoteKlineRequest;
import com.tigerbrokers.stock.openapi.client.https.response.quote.MarketScannerResponse;
import com.tigerbrokers.stock.openapi.client.https.response.quote.QuoteKlineResponse;
import com.tigerbrokers.stock.openapi.client.struct.enums.KType;
import com.tigerbrokers.stock.openapi.client.struct.enums.Market;
import com.tigerbrokers.stock.openapi.client.struct.enums.StockField;

/**
 * 老虎证券服务实现
 * 基于老虎证券量化平台API实现
 * @see <a href="https://quant.itigerup.com/openapi/zh/java/operation/quotation/stock.html">老虎证券API文档</a>
 */
@Service("tigerStockService")
public class TigerStockServiceImpl implements DataSourceStrategy {
    
    private static final Logger log = LoggerFactory.getLogger(TigerStockServiceImpl.class);

    private final ObjectMapper objectMapper;
    private final TigerHttpClient client;
    private final PatternEvaluateService patternEvaluateService;

    public TigerStockServiceImpl(ObjectMapper objectMapper,
                                 @Autowired(required = true) TigerHttpClient client,
                                 PatternEvaluateService patternEvaluateService) {
        log.info("TigerStockServiceImpl: Service initialized");
        this.client = client;
        this.objectMapper = objectMapper;
        this.patternEvaluateService = patternEvaluateService;
    }

    @Override
    public String getSourceName() {
        return "tiger";
    }

    @Override
    public boolean isAvailable() {
        return client != null;
    }

    public boolean isClientAvailable() {
        return true;
    }
    public String getDailyKLineData(String symbol) {
        try {
            
            // 创建K线请求
            QuoteKlineRequest request = QuoteKlineRequest.newRequest(
                Collections.singletonList(symbol),
                KType.day, 
                ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalDate().minusDays(30).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                .withLimit(30);
            
            // 发送请求
            QuoteKlineResponse response = client.execute(request);
            
            // 检查响应
            if (response == null || !response.isSuccess()) {
                log.error("获取K线数据失败: {}", response == null ? "响应为空" : response.getMessage());
                return "{}";
            }
            return objectMapper.writeValueAsString(response.getKlineItems());
        } catch (Exception e) {
            log.warn("获取K线数据时出错: {}", e.getMessage());
            return "{}";
        }
    }
    public KLineData getDailyKLineDataAsObject(String symbol) {
        try {
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of("America/New_York"));
            QuoteKlineRequest request = QuoteKlineRequest.newRequest(
                Collections.singletonList(symbol),
                KType.day,
                now.minusDays(30).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                .withLimit(30);
            QuoteKlineResponse response = client.execute(request);
            if (response == null || !response.isSuccess()) {
                return new KLineData();
            }
            KLineData kLineData = new KLineData();
            kLineData.setSymbol(symbol);
            List<KLineIterator> items = new ArrayList<>();
            for (KlineItem item : response.getKlineItems()) {
                parseAndAddKLineIterator(items, item);
            }
            kLineData.setItems(items);
            return kLineData;
        } catch (Exception e) {
            log.warn("获取K线数据时出错: {}", e.getMessage());
            return new KLineData();
        }
    }

    @Override
    public KLineData getDailyKLineDataByDateRange(String symbol, LocalDate tradeDate) {
        try {
            log.info("[TigerStockServiceImpl] dateRange symbol={}, range=[{},{}]", symbol, tradeDate, tradeDate);
            String dateStr = tradeDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            QuoteKlineRequest request = QuoteKlineRequest.newRequest(
                Collections.singletonList(symbol),
                KType.day,
                dateStr,
                dateStr)
                .withLimit(30);
            QuoteKlineResponse response = client.execute(request);
            if (response == null || !response.isSuccess()) {
                log.error("获取K线数据失败: {}", response == null ? "响应为空" : response.getMessage());
                return new KLineData();
            }
            KLineData kLineData = new KLineData();
            kLineData.setSymbol(symbol);
            List<KLineIterator> items = new ArrayList<>();
            for (KlineItem item : response.getKlineItems()) {
                parseAndAddKLineIterator(items, item);
            }
            kLineData.setItems(items);
            return kLineData;
        } catch (Exception e) {
            log.warn("获取K线数据时出错: {}", e.getMessage());
            return new KLineData();
        }
    }
    
    /**
     * 解析并添加K线迭代器
     * @param items K线迭代器列表
     * @param item K线数据项
     */
    private void parseAndAddKLineIterator(List<KLineIterator> items, KlineItem item) {
        try {
            for (KlinePoint point : item.getItems()) {
                KLineIterator iterator = convertTigerKlinePointToKLineIterator(item.getSymbol(), point);
                items.add(iterator);
            }
        } catch (Exception e) {
            log.warn("解析K线数据时出错: {}", e.getMessage());
        }
    }
    public Map<String, Object> scanLowPriceStocksWithVolumePattern(int limit) {
        try {

            // 获取低价股票列表
            List<String> lowPriceStocks = scanStocks(Market.US, limit, 0.0, 100.0);

            // 创建结果Map
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total", lowPriceStocks.size());

            // 检查每只股票的交易量模式
            List<String> matchedStocks = new ArrayList<>();
            for (String symbol : lowPriceStocks) {
                KLineData kLineData = getDailyKLineDataAsObject(symbol);
                if (kLineData != null && kLineData.getItems() != null && kLineData.getItems().size() >= 7) {
                    // Convert newest-first to oldest-first for volume pattern evaluation
                    List<KLineIterator> oldestFirst = new ArrayList<>(kLineData.getItems());
                    Collections.reverse(oldestFirst);
                    if (patternEvaluateService.matchesIncreasingVolumePatternFromKLine(oldestFirst, 7)) {
                        matchedStocks.add(symbol);
                    }
                }
            }

            // 设置结果
            result.put("matched", matchedStocks.size());
            result.put("stocks", matchedStocks);

            return result;
        } catch (Exception e) {
            log.warn("扫描低价股票时出错: {}", e.getMessage());
            return new HashMap<>();
        }
    }
    public Map<String, Object> scanLowPriceStocksWithVolumePattern() {
        return scanLowPriceStocksWithVolumePattern(20);
    }

    public StockInfo getStockInfo(String symbol) {
        try {
            
            // 获取K线数据
            KLineData kLineData = getDailyKLineDataAsObject(symbol);
            if (kLineData == null || kLineData.getItems().isEmpty()) {
                StockInfo emptyInfo = new StockInfo();
                emptyInfo.setSymbol(symbol);
                return emptyInfo;
            }
            
            // 获取最新的K线数据
            KLineIterator latest = kLineData.getItems().get(0);
            
            // 创建股票信息对象
            StockInfo stockInfo = new StockInfo();
            stockInfo.setSymbol(symbol);
            stockInfo.setCurrentPrice(latest.getClose());
            stockInfo.setVolume(latest.getVolume());
            stockInfo.setChange(latest.getClose() - latest.getOpen());
            stockInfo.setChangePercent((latest.getClose() - latest.getOpen()) / latest.getOpen() * 100);
            
            return stockInfo;
        } catch (Exception e) {
            log.warn("获取股票信息时出错: {}", e.getMessage());
            StockInfo emptyInfo = new StockInfo();
            emptyInfo.setSymbol(symbol);
            return emptyInfo;
        }
    }
    public List<String> getStockList() {
        return getStocksFromTigerApi(Market.US, 100, null, null);
    }
    
    /**
     * 获取备用股票列表
     * @param market 市场
     * @param limit 限制数量
     * @return 股票代码列表
     */
    private static List<String> getFallbackStockList(Market market, int limit) {
        List<String> stocks = new ArrayList<>();
        
        // 添加一些知名股票作为备用
        if (market == Market.US) {
            stocks.addAll(Arrays.asList(
                "AAPL", "MSFT", "GOOGL", "AMZN", "META",
                "TSLA", "NVDA", "JPM", "V", "WMT",
                "PG", "MA", "HD", "BAC", "INTC",
                "VZ", "DIS", "NFLX", "ADBE", "CSCO"
            ));
        }
        
        // 限制数量
        return stocks.stream().limit(limit).collect(Collectors.toList());
    }
    
    /**
     * 从Tiger API获取股票列表
     * @param market 市场
     * @param limit 限制数量
     * @param minPrice 最低价格
     * @param maxPrice 最高价格
     * @return 股票代码列表
     */
    private List<String> getStocksFromTigerApi(Market market,
                                               int limit, Double minPrice, Double maxPrice) {
        try {
            
            // 创建市场扫描请求
            List<BaseFilter> filters = new ArrayList<>();
            if (minPrice != null || maxPrice != null) {
                BaseFilter priceFilter = BaseFilter.builder()
                    .fieldName(StockField.StockField_CurPrice)
                    .filterMin(minPrice)
                    .filterMax(maxPrice)
                    .build();
                filters.add(priceFilter);
            }
            
            MarketScannerRequest request = MarketScannerRequest.newRequest(
                market,
                filters,
                null,
                null,
                null,
                null,
                1,
                limit
            );
            
            // 发送请求
            MarketScannerResponse response = client.execute(request);
            
            // 检查响应
            if (response == null || !response.isSuccess()) {
                log.error("获取股票列表失败: {}", response == null ? "响应为空" : response.getMessage());
                return getFallbackStockList(market, limit);
            }
            
            // 直接从响应中提取股票代码
            if (response.getMarketScannerBatchItem() == null
                || response.getMarketScannerBatchItem().getItems() == null) {
                return Collections.emptyList();
            }
            return response.getMarketScannerBatchItem().getItems().stream()
                .map(item -> item.getSymbol())
                .filter(symbol -> symbol != null && !symbol.isEmpty())
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("获取股票列表时出错: {}", e.getMessage());
            return getFallbackStockList(market, limit);
        }
    }
    public List<String> scanStocks(Market market, int limit, Double minPrice, Double maxPrice) {
        return getStocksFromTigerApi(market, limit, minPrice, maxPrice);
    }
    public List<String> scanStocks(String market, int limit, String minPrice, String maxPrice) {
        try {
            Market marketEnum = Market.valueOf(market.toUpperCase());
            Double minPriceDouble = minPrice != null ? Double.parseDouble(minPrice) : null;
            Double maxPriceDouble = maxPrice != null ? Double.parseDouble(maxPrice) : null;
            return scanStocks(marketEnum, limit, minPriceDouble, maxPriceDouble);
        } catch (Exception e) {
            log.warn("扫描股票时出错: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
    public List<KLineData> getBatchKline(List<String> symbols, String period, int count) {
        try {
            
            // 创建结果列表
            List<KLineData> result = new ArrayList<>();
            
            // 获取每只股票的K线数据
            for (String symbol : symbols) {
                // 解析K线周期
                KType kType;
                try {
                    kType = KType.valueOf(period.toLowerCase());
                } catch (IllegalArgumentException e) {
                    log.error("Invalid KType period value: {}", period, e);
                    throw e;
                }

                // 创建K线请求
                QuoteKlineRequest request = QuoteKlineRequest.newRequest(
                    Collections.singletonList(symbol),
                    kType,
                    ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalDate().minusDays(count).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                    ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                    .withLimit(count);
                
                // 发送请求
                QuoteKlineResponse response = client.execute(request);
                
                // 检查响应
                if (response == null || !response.isSuccess()) {
                    log.error("获取K线数据失败: {}", response == null ? "响应为空" : response.getMessage());
                    continue;
                }
                if (response.getKlineItems() == null || response.getKlineItems().isEmpty()) {
                    continue;
                }
                // 转换K线数据
                KLineData kLineData = convertTigerKlineItemToKLineData(response.getKlineItems().get(0));
                result.add(kLineData);
            }
            
            return result;
        } catch (Exception e) {
            log.warn("获取批量K线数据时出错: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * 转换Tiger K线数据为KLineData对象
     * @param tigerItem Tiger K线数据
     * @return KLineData对象
     */
    private static KLineData convertTigerKlineItemToKLineData(KlineItem tigerItem) {
        KLineData kLineData = new KLineData();
        kLineData.setSymbol(tigerItem.getSymbol());
        
        List<KLineIterator> items = new ArrayList<>();
        for (KlinePoint point : tigerItem.getItems()) {
            KLineIterator iterator = convertTigerKlinePointToKLineIterator(tigerItem.getSymbol(), point);
            items.add(iterator);
        }
        
        kLineData.setItems(items);
        return kLineData;
    }
    
    /**
     * 转换Tiger K线点位为KLineIterator对象
     * @param symbol 股票代码
     * @param point Tiger K线点位
     * @return KLineIterator对象
     */
    private static KLineIterator convertTigerKlinePointToKLineIterator(String symbol, KlinePoint point) {
        return new KLineIterator(
            symbol,
            point.getTime(),
            point.getOpen(),
            point.getHigh(),
            point.getLow(),
            point.getClose(),
            point.getVolume(),
            point.getAmount()
        );
    }
    public KLineData getDailyKLine(String symbol) {
        return getDailyKLineDataAsObject(symbol);
    }
}
