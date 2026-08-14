package com.stock.invest.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.config.TiingoProperties;
import com.stock.invest.exception.StockDataException;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.StockInfo;
import com.stock.invest.service.StockScannerStrategy;
import com.stock.invest.util.PythonScriptExecutor;
import com.tigerbrokers.stock.openapi.client.struct.enums.Market;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Tiingo 数据源 —— 完全基于 Python SDK（tiingo 包）实现，替代原 Java HTTP client。
 * <p>
 * 通过 {@link PythonScriptExecutor} 调用 {@code stock_info_tiingo.py}（classpath 下）：
 * <ul>
 *   <li>get_daily_kline_range(symbol, start, end) —— 按日期范围日K（补缺核心）</li>
 *   <li>get_daily_kline(symbol, days) —— 最近 N 天日K</li>
 *   <li>get_batch_kline(symbols, period, count) —— 批量日K</li>
 * </ul>
 * 低价股扫描（原 IEX /iex/ 端点）确认用不到，getStockList/scanStocks 空实现（2026-08-14）。
 */
@Component
@Order(5)
public class TiingoDataSourceStrategy implements StockScannerStrategy {

    private static final Logger log = LoggerFactory.getLogger(TiingoDataSourceStrategy.class);

    private final PythonScriptExecutor pythonScriptExecutor;
    private final TiingoProperties tiingoProperties;
    private final ObjectMapper objectMapper;

    public TiingoDataSourceStrategy(PythonScriptExecutor pythonScriptExecutor,
                                    TiingoProperties tiingoProperties,
                                    ObjectMapper objectMapper) {
        this.pythonScriptExecutor = pythonScriptExecutor;
        this.tiingoProperties = tiingoProperties;
        this.objectMapper = objectMapper;
        log.info("TiingoDataSourceStrategy: Service initialized (python SDK mode)");
    }

    @Override
    public String getSourceName() {
        return "tiingo";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    private static String getScriptName() {
        return "stock_info_tiingo.py";
    }

    private Map<String, String> apiKeyEnv() {
        String token = tiingoProperties.getToken();
        if (token == null || token.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        return Map.of("TIINGO_API_KEY", token.trim());
    }

    @Override
    public String getDailyKLineData(String symbol) {
        KLineData kLineData = getDailyKLineDataAsObject(symbol);
        return kLineData == null ? "{}" : kLineData.toString();
    }

    @Override
    public KLineData getDailyKLineDataAsObject(String symbol) {
        try {
            String result = pythonScriptExecutor.executeScriptWithEnvironment(
                    apiKeyEnv(), getScriptName(), "get_daily_kline", symbol, "30");
            if (result != null && result.contains("\"error\"")) {
                log.warn("[Tiingo] getDailyKLineDataAsObject error for {}: {}", symbol,
                        result.substring(0, Math.min(result.length(), 500)));
                return new KLineData();
            }
            return parseKLineData(symbol, result);
        } catch (Exception e) {
            log.warn("tiingo getDailyKLineDataAsObject failed for {}: {}", symbol, e.getMessage());
            return new KLineData();
        }
    }

    @Override
    public KLineData getDailyKLineDataByDateRange(String symbol, LocalDate tradeDate) {
        try {
            log.info("[TiingoDataSourceStrategy] dateRange symbol={}, range=[{},{}]", symbol, tradeDate, tradeDate);
            String result = pythonScriptExecutor.executeScriptWithEnvironment(
                    apiKeyEnv(), getScriptName(), "get_daily_kline_range",
                    symbol, tradeDate.toString(), tradeDate.toString());
            if (result != null && result.contains("\"error\"")) {
                String errMsg = extractErrorFromJson(result);
                log.warn("[Tiingo] getDailyKLineDataByDateRange error for {}: {}", symbol, errMsg);
                // P1-3：Python 侧失败 —— 带分类抛出，not-found 才计入黑名单
                throw StockDataException.classify(symbol, "tiingo", errMsg, null);
            }
            return parseKLineData(symbol, result);
        } catch (StockDataException e) {
            throw e;
        } catch (Exception e) {
            log.warn("tiingo getDailyKLineDataByDateRange failed for {}: {}", symbol, e.getMessage());
            throw new StockDataException(symbol, "tiingo", "获取K线数据失败: " + e.getMessage(),
                    e, StockDataException.ErrorCategory.TRANSIENT_FAILURE);
        }
    }

    @Override
    public StockInfo getStockInfo(String symbol) {
        try {
            KLineData data = getDailyKLineDataAsObject(symbol);
            if (data == null || data.getItems() == null || data.getItems().isEmpty()) {
                return null;
            }
            com.stock.invest.model.KLineIterator latest = data.getItems().get(0);
            StockInfo info = new StockInfo();
            info.setSymbol(symbol);
            info.setCurrentPrice(latest.getClose());
            info.setOpenPrice(latest.getOpen());
            info.setVolume(latest.getVolume());
            // 标准涨跌幅计算：(今日收盘 - 昨日收盘) / 昨日收盘 * 100
            if (data.getItems().size() >= 2) {
                com.stock.invest.model.KLineIterator prev = data.getItems().get(1);
                java.math.BigDecimal prevClose = prev.getClose();
                java.math.BigDecimal change = latest.getClose().subtract(prevClose);
                info.setChange(change);
                if (prevClose.compareTo(java.math.BigDecimal.ZERO) != 0) {
                    // R2 P3-4：精度统一 —— setScale(4, HALF_UP)，与 DB DECIMAL(12,4) 对齐
                    info.setChangePercent(change
                            .divide(prevClose, 8, java.math.RoundingMode.HALF_UP)
                            .multiply(java.math.BigDecimal.valueOf(100))
                            .setScale(4, java.math.RoundingMode.HALF_UP));
                }
            } else {
                info.setChange(java.math.BigDecimal.ZERO);
                info.setChangePercent(java.math.BigDecimal.ZERO);
            }
            return info;
        } catch (Exception e) {
            log.warn("tiingo getStockInfo failed for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    @Override
    public List<String> getStockList() {
        // 低价股扫描（原 IEX）已确认用不到 —— 空实现（2026-08-14）
        return Collections.emptyList();
    }

    @Override
    public KLineData getDailyKLine(String symbol) {
        return getDailyKLineDataAsObject(symbol);
    }

    @Override
    public List<KLineData> getBatchKline(List<String> symbols, String period, int count) {
        try {
            String result = pythonScriptExecutor.executeScriptWithEnvironment(
                    apiKeyEnv(), getScriptName(), "get_batch_kline",
                    String.join(",", symbols), period, String.valueOf(count));
            if (result != null && result.contains("\"error\"")) {
                log.warn("[Tiingo] getBatchKline error: {}", result.substring(0, Math.min(result.length(), 500)));
                return Collections.emptyList();
            }
            List<KLineData> out = new ArrayList<>();
            JsonNode arr = objectMapper.readTree(result);
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    KLineData data = objectMapper.treeToValue(node, KLineData.class);
                    if (data != null && data.getItems() != null && !data.getItems().isEmpty()) {
                        out.add(data);
                    }
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("tiingo getBatchKline failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<String> scanStocks(Market market, int limit, Double minPrice, Double maxPrice) {
        // 低价股扫描（原 IEX）已确认用不到 —— 空实现（2026-08-14）
        return Collections.emptyList();
    }

    @Override
    public List<String> scanStocks(String market, int limit, String minPrice, String maxPrice) {
        // 低价股扫描（原 IEX）已确认用不到 —— 空实现（2026-08-14）
        return Collections.emptyList();
    }

    @Override
    public Map<String, Object> scanLowPriceStocksWithVolumePattern(int limit) {
        return Collections.emptyMap();
    }

    /**
     * 解析 Python 脚本输出的 KLineData JSON（{"symbol": ..., "items": [...]}）。
     */
    private KLineData parseKLineData(String symbol, String json) throws Exception {
        KLineData klineData = objectMapper.readValue(json, KLineData.class);
        // 填充每个 item 的 symbol 字段（脚本 JSON 中 item 不含 symbol）
        if (klineData != null && klineData.getItems() != null) {
            for (com.stock.invest.model.KLineIterator item : klineData.getItems()) {
                item.setSymbol(symbol);
            }
        }
        return klineData;
    }

    /**
     * 从 Python 脚本输出的 error JSON 中提取可读错误消息。
     */
    private String extractErrorFromJson(String result) {
        try {
            JsonNode node = objectMapper.readTree(result);
            JsonNode err = node.get("error");
            if (err == null) {
                return result;
            }
            if (err.isTextual()) {
                return err.asText();
            }
            if (err.has("message")) {
                return err.path("message").asText();
            }
            return err.toString();
        } catch (Exception ignored) {
            return result;
        }
    }
}
