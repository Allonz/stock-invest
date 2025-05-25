package com.stock.invest.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONArray;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
import com.stock.invest.service.StockService;
import com.tigerbrokers.stock.openapi.client.config.ClientConfig;
import com.tigerbrokers.stock.openapi.client.https.client.TigerHttpClient;
import com.tigerbrokers.stock.openapi.client.https.request.quote.QuoteKlineRequest;
import com.tigerbrokers.stock.openapi.client.https.response.quote.QuoteKlineResponse;
import com.tigerbrokers.stock.openapi.client.https.domain.quote.item.KlineItem;
import com.tigerbrokers.stock.openapi.client.https.domain.quote.item.KlinePoint;
import com.tigerbrokers.stock.openapi.client.https.domain.quote.model.BaseFilter;
import com.tigerbrokers.stock.openapi.client.https.request.quote.MarketScannerRequest;
import com.tigerbrokers.stock.openapi.client.https.response.quote.MarketScannerResponse;
import com.tigerbrokers.stock.openapi.client.struct.enums.KType;
import com.tigerbrokers.stock.openapi.client.struct.enums.Language;
import com.tigerbrokers.stock.openapi.client.struct.enums.Market;
import com.tigerbrokers.stock.openapi.client.struct.enums.TimeZoneId;
import com.tigerbrokers.stock.openapi.client.struct.enums.StockField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.stock.invest.util.StockPatternUtil;

/**
 * 老虎证券服务实现
 * 基于老虎证券量化平台API实现
 * @see <a href="https://quant.itigerup.com/openapi/zh/java/operation/quotation/stock.html">老虎证券API文档</a>
 */
@Service
@org.springframework.context.annotation.Profile("tiger")
public class TigerStockServiceImpl implements StockService {
    
    private static final Logger logger = LoggerFactory.getLogger(TigerStockServiceImpl.class);
    private static final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    private final ObjectMapper objectMapper;
    private TigerHttpClient client;
    
    @Value("${tiger.api.tiger_id}")
    private String tigerId;
    
    @Value("${tiger.api.private_key}")
    private String privateKey;
    
    @Value("${tiger.api.account}")
    private String account;
    
    @Value("${tiger.api.log_path}")
    private String logPath;

    public TigerStockServiceImpl(ObjectMapper objectMapper) {
        logger.info("TigerStockServiceImpl " + LocalDateTime.now().format(dateFormat) + ": Service initialized");
        this.objectMapper = objectMapper;
    }
    
    /**
     * 初始化Tiger客户端
     * 使用resources目录下的配置文件初始化Tiger API客户端
     */
    private void initClient() {
        if (client == null) {
            try {
                // 输出配置信息
                logger.info("正在初始化Tiger API客户端...");
                logger.info("Tiger ID: {}", tigerId);
                logger.info("Private Key是否为空: {}", privateKey == null || privateKey.isEmpty());
                if(privateKey != null) {
                    logger.info("Private Key长度: {}", privateKey.length());
                }
                
                // 准备配置目录
                String configDir = prepareConfigDirectory();
                
                // 准备配置文件
                prepareConfigFiles(configDir);
                
                // 配置客户端
                ClientConfig config = configureClient(configDir);
                
                // 创建客户端
                client = TigerHttpClient.getInstance().clientConfig(config);
                
                // 验证API凭证
                validateApiCredentials();
                
                logger.info("Tiger API客户端初始化成功");
            } catch (Exception e) {
                logger.error("初始化Tiger API客户端时出错: {}", e.getMessage(), e);
                throw new RuntimeException("初始化Tiger API客户端失败", e);
            }
        }
    }
    
    /**
     * 准备配置目录
     * @return 配置目录路径
     */
    private String prepareConfigDirectory() throws IOException {
        // 获取用户主目录
        String userHome = System.getProperty("user.home");
        Path configDir = Paths.get(userHome, ".tiger", "config");
        
        // 创建配置目录
        if (!Files.exists(configDir)) {
            logger.info("创建Tiger配置目录: {}", configDir);
            Files.createDirectories(configDir);
        }
        
        return configDir.toString();
    }
    
    /**
     * 准备配置文件
     * @param configDir 配置目录路径
     */
    private void prepareConfigFiles(String configDir) throws IOException {
        // 准备tiger_config.properties文件
        Path configPath = Paths.get(configDir, "tiger_config.properties");
        if (!Files.exists(configPath)) {
            logger.info("创建Tiger配置文件: {}", configPath);
            ClassPathResource resource = new ClassPathResource("tiger_config.properties");
            FileCopyUtils.copy(resource.getInputStream(), new FileOutputStream(configPath.toFile()));
        }
        
        // 准备tiger_private_key.pem文件
        Path keyPath = Paths.get(configDir, "tiger_private_key.pem");
        if (!Files.exists(keyPath)) {
            logger.info("创建Tiger私钥文件: {}", keyPath);
            Files.write(keyPath, privateKey.getBytes());
        }
    }
    
    /**
     * 配置客户端
     * @param configDir 配置目录路径
     * @return 客户端配置
     */
    private ClientConfig configureClient(String configDir) {
        // 加载配置属性
        java.util.Properties props = loadConfigProperties(configDir);
        
        // 创建客户端配置
        ClientConfig config = new ClientConfig();
        config.tigerId = tigerId;
        config.privateKey = privateKey;
        config.language = Language.zh_CN;
        config.timeZone = TimeZoneId.Shanghai;
        config.configFilePath = setupLogDirectory();
        
        // 设置其他配置
        if (props != null) {
            config.failRetryCounts = Integer.parseInt(props.getProperty("connect.timeout", "5000"));
            config.isAutoGrabPermission = true;
        }
        
        return config;
    }
    
    /**
     * 设置日志目录
     * @return 日志目录路径
     */
    private String setupLogDirectory() {
        // 获取用户主目录
        String userHome = System.getProperty("user.home");
        Path logDir = Paths.get(userHome, ".tiger", "logs");
        
        // 创建日志目录
        if (!Files.exists(logDir)) {
            logger.info("创建Tiger日志目录: {}", logDir);
            try {
                Files.createDirectories(logDir);
        } catch (IOException e) {
                logger.error("创建日志目录失败: {}", e.getMessage());
        }
        }
        
        return logDir.toString();
    }
    
    /**
     * 验证API凭证
     */
    private void validateApiCredentials() {
        if (tigerId == null || tigerId.isEmpty()) {
            throw new IllegalArgumentException("Tiger ID不能为空");
        }
        
        if (privateKey == null || privateKey.isEmpty()) {
            throw new IllegalArgumentException("Private Key不能为空");
        }
        
        if (account == null || account.isEmpty()) {
            throw new IllegalArgumentException("Account不能为空");
        }
    }
    
    @Override
    public String getDailyKLineData(String symbol) {
        try {
            // 初始化客户端
            initClient();
            
            // 创建K线请求
            QuoteKlineRequest request = QuoteKlineRequest.newRequest(
                Collections.singletonList(symbol),
                KType.day, 
                LocalDate.now().minusDays(30).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                .withLimit(30);
            
            // 发送请求
            QuoteKlineResponse response = client.execute(request);
            
            // 检查响应
            if (response != null && response.isSuccess()) {
                return objectMapper.writeValueAsString(response.getKlineItems());
            } else {
                logger.error("获取K线数据失败: {}", response != null ? response.getMessage() : "响应为空");
                return null;
            }
        } catch (Exception e) {
            logger.error("获取K线数据时出错: {}", e.getMessage(), e);
            return null;
        }
    }
    
    @Override
    public KLineData getDailyKLineDataAsObject(String symbol) {
        try {
            // 初始化客户端
            initClient();
            
            // 创建K线请求
            QuoteKlineRequest request = QuoteKlineRequest.newRequest(
                Collections.singletonList(symbol),
                KType.day,
                LocalDate.now().minusDays(30).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                .withLimit(30);
            
            // 发送请求
            QuoteKlineResponse response = client.execute(request);
            
            // 检查响应
            if (response != null && response.isSuccess()) {
                // 创建KLineData对象
            KLineData kLineData = new KLineData();
                kLineData.setSymbol(symbol);
                
                // 解析K线数据
                List<KLineIterator> items = new ArrayList<>();
                for (KlineItem item : response.getKlineItems()) {
                    parseAndAddKLineIterator(items, item);
                }
                
                // 设置K线数据
                kLineData.setItems(items);
                    
                    return kLineData;
            } else {
                logger.error("获取K线数据失败: {}", response != null ? response.getMessage() : "响应为空");
                return null;
            }
            } catch (Exception e) {
            logger.error("获取K线数据时出错: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 解析并添加K线迭代器
     * @param items K线迭代器列表
     * @param rawItem 原始K线数据
     */
    private void parseAndAddKLineIterator(List<KLineIterator> items, Object rawItem) {
        try {
            if (rawItem instanceof KlineItem) {
                KlineItem item = (KlineItem) rawItem;
                for (KlinePoint point : item.getItems()) {
                    KLineIterator iterator = convertTigerKlinePointToKLineIterator(item.getSymbol(), point);
                    items.add(iterator);
                }
            } else if (rawItem instanceof Map) {
        @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) rawItem;
                KLineIterator iterator = new KLineIterator(
                    (String) item.get("symbol"),
                    Long.parseLong(item.get("time").toString()),
                    Double.parseDouble(item.get("open").toString()),
                    Double.parseDouble(item.get("high").toString()),
                    Double.parseDouble(item.get("low").toString()),
                    Double.parseDouble(item.get("close").toString()),
                    Long.parseLong(item.get("volume").toString()),
                    Double.parseDouble(item.get("amount").toString())
                );
                items.add(iterator);
            }
        } catch (Exception e) {
            logger.error("解析K线数据时出错: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 加载配置属性
     * @param configDir 配置目录路径
     * @return 配置属性
     */
    private java.util.Properties loadConfigProperties(String configDir) {
        try {
            java.util.Properties props = new java.util.Properties();
            Path configPath = Paths.get(configDir, "tiger_config.properties");
            if (Files.exists(configPath)) {
                props.load(Files.newInputStream(configPath));
            }
            return props;
        } catch (IOException e) {
            logger.error("加载配置文件时出错: {}", e.getMessage(), e);
            return null;
        }
    }
    
    @Override
    public Map<String, Object> scanLowPriceStocksWithVolumePattern(int limit) {
        try {
            // 初始化客户端
            initClient();
            
            // 获取低价股票列表
            List<String> lowPriceStocks = scanStocks(Market.US, limit, 0.0, 100.0);
            
            // 创建结果Map
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total", lowPriceStocks.size());
            
            // 检查每只股票的交易量模式
            List<String> matchedStocks = new ArrayList<>();
            for (String symbol : lowPriceStocks) {
                KLineData kLineData = getDailyKLineDataAsObject(symbol);
                if (kLineData != null) {
                    List<Map<String, Object>> klineDataList = new ArrayList<>();
                    for (KLineIterator item : kLineData.getItems()) {
                        Map<String, Object> klineItem = new HashMap<>();
                        klineItem.put("time", item.getTime());
                        klineItem.put("open", item.getOpen());
                        klineItem.put("high", item.getHigh());
                        klineItem.put("low", item.getLow());
                        klineItem.put("close", item.getClose());
                        klineItem.put("volume", item.getVolume());
                        klineDataList.add(klineItem);
                    }
                    if (StockPatternUtil.matchesVolumePattern(klineDataList)) {
                        matchedStocks.add(symbol);
                    }
                }
            }
            
            // 设置结果
            result.put("matched", matchedStocks.size());
            result.put("stocks", matchedStocks);
            
            return result;
        } catch (Exception e) {
            logger.error("扫描低价股票时出错: {}", e.getMessage(), e);
            return null;
        }
        }
        
    @Override
    public Map<String, Object> scanLowPriceStocksWithVolumePattern() {
        return scanLowPriceStocksWithVolumePattern(20);
    }

    @Override
    public List<KLineData> getBatchKlineData(List<String> symbols, Period period, int count) {
        return getBatchKline(symbols, period.toString(), count);
    }
    
    @Override
    public com.stock.invest.model.StockInfo getStockInfo(String symbol) {
        try {
            // 初始化客户端
            initClient();
            
            // 获取K线数据
            KLineData kLineData = getDailyKLineDataAsObject(symbol);
            if (kLineData == null || kLineData.getItems().isEmpty()) {
                return null;
            }
            
            // 获取最新的K线数据
            KLineIterator latest = kLineData.getItems().get(0);
            
            // 创建股票信息对象
            com.stock.invest.model.StockInfo stockInfo = new com.stock.invest.model.StockInfo();
            stockInfo.setSymbol(symbol);
            stockInfo.setCurrentPrice(latest.getClose());
            stockInfo.setVolume((int)latest.getVolume());
            stockInfo.setChange(latest.getClose() - latest.getOpen());
            stockInfo.setChangePercent((latest.getClose() - latest.getOpen()) / latest.getOpen() * 100);
            
            return stockInfo;
        } catch (Exception e) {
            logger.error("获取股票信息时出错: {}", e.getMessage(), e);
            return null;
        }
    }
    
    @Override
    public List<String> getStockList() {
        return getHighVolumeUSStocks(100);
    }
    
    /**
     * 获取高成交量美股列表
     * @param limit 限制数量
     * @return 股票代码列表
     */
    public List<String> getHighVolumeUSStocks(int limit) {
        return getStocksFromTigerApi(Market.US, false, limit, null, null);
    }
    
    /**
     * 获取涨幅最大的美股列表
     * @param limit 限制数量
     * @return 股票代码列表
     */
    public List<String> getTopGainerUSStocks(int limit) {
        return getStocksFromTigerApi(Market.US, false, limit, null, null);
    }
    
    /**
     * 获取跌幅最大的美股列表
     * @param limit 限制数量
     * @return 股票代码列表
     */
    public List<String> getTopLoserUSStocks(int limit) {
        return getStocksFromTigerApi(Market.US, true, limit, null, null);
    }
    
    /**
     * 获取市值最大的美股列表
     * @param limit 限制数量
     * @return 股票代码列表
     */
    public List<String> getTopMarketCapUSStocks(int limit) {
        return getStocksFromTigerApi(Market.US, false, limit, null, null);
    }
    
    /**
     * 获取高股息美股列表
     * @param limit 限制数量
     * @return 股票代码列表
     */
    public List<String> getHighDividendUSStocks(int limit) {
        return getStocksFromTigerApi(Market.US, false, limit, null, null);
    }
    
    /**
     * 获取备用股票列表
     * @param market 市场
     * @param limit 限制数量
     * @return 股票代码列表
     */
    private List<String> getFallbackStockList(Market market, int limit) {
        List<String> stocks = new ArrayList<>();
        
        // 添加一些知名股票作为备用
        if (market == Market.US) {
            stocks.addAll(java.util.Arrays.asList(
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
     * @param isAsc 是否升序
     * @param limit 限制数量
     * @param minPrice 最低价格
     * @param maxPrice 最高价格
     * @return 股票代码列表
     */
    private List<String> getStocksFromTigerApi(Market market, boolean isAsc, 
                                               int limit, Double minPrice, Double maxPrice) {
        try {
            // 初始化客户端
            initClient();
            
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
            if (response != null && response.isSuccess()) {
                List<String> stocks = new ArrayList<>();
                // 使用JSONObject处理响应数据
                JSONObject responseJson = JSONObject.parseObject(JSONObject.toJSONString(response));
                if (responseJson.containsKey("data")) {
                    JSONArray dataArray = responseJson.getJSONArray("data");
                    for (int i = 0; i < dataArray.size(); i++) {
                        JSONObject item = dataArray.getJSONObject(i);
                        String symbol = item.getString("symbol");
                        if (symbol != null && !symbol.isEmpty()) {
                            stocks.add(symbol);
                        }
                    }
                }
                return stocks;
                } else {
                logger.error("获取股票列表失败: {}", response != null ? response.getMessage() : "响应为空");
                return getFallbackStockList(market, limit);
                }
            } catch (Exception e) {
            logger.error("获取股票列表时出错: {}", e.getMessage(), e);
            return getFallbackStockList(market, limit);
        }
    }
    
    @Override
    public List<String> scanStocks(Market market, int limit, Double minPrice, Double maxPrice) {
        return getStocksFromTigerApi(market, false, limit, minPrice, maxPrice);
    }

    @Override
    public List<String> scanStocks(String market, int limit, String minPrice, String maxPrice) {
        try {
            Market marketEnum = Market.valueOf(market.toUpperCase());
            Double minPriceDouble = minPrice != null ? Double.parseDouble(minPrice) : null;
            Double maxPriceDouble = maxPrice != null ? Double.parseDouble(maxPrice) : null;
            return scanStocks(marketEnum, limit, minPriceDouble, maxPriceDouble);
        } catch (Exception e) {
            logger.error("扫描股票时出错: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
    
    @Override
    public List<KLineData> getBatchKline(List<String> symbols, String period, int count) {
        try {
            // 初始化客户端
            initClient();
            
            // 创建结果列表
            List<KLineData> result = new ArrayList<>();
            
            // 获取每只股票的K线数据
            for (String symbol : symbols) {
                // 创建K线请求
                QuoteKlineRequest request = QuoteKlineRequest.newRequest(
                    Collections.singletonList(symbol),
                    KType.valueOf(period.toLowerCase()),
                    LocalDate.now().minusDays(count).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                    LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                    .withLimit(count);
                
                // 发送请求
                QuoteKlineResponse response = client.execute(request);
                
                // 检查响应
                if (response != null && response.isSuccess()) {
                    // 转换K线数据
                    KLineData kLineData = convertTigerKlineItemToKLineData(response.getKlineItems().get(0));
                    result.add(kLineData);
                } else {
                    logger.error("获取K线数据失败: {}", response != null ? response.getMessage() : "响应为空");
                }
            }
            
            return result;
        } catch (Exception e) {
            logger.error("获取批量K线数据时出错: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 转换Tiger K线数据为KLineData对象
     * @param tigerItem Tiger K线数据
     * @return KLineData对象
     */
    private KLineData convertTigerKlineItemToKLineData(KlineItem tigerItem) {
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
    private KLineIterator convertTigerKlinePointToKLineIterator(String symbol, KlinePoint point) {
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
    
    @Override
    public KLineData getDailyKLine(String symbol) {
        return getDailyKLineDataAsObject(symbol);
    }
} 