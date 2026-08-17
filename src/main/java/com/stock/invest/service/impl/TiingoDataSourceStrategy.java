package com.stock.invest.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.config.TiingoProperties;
import com.stock.invest.exception.StockDataException;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
import com.stock.invest.service.DataSourceStrategy;
import com.stock.invest.util.PythonScriptExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collections;
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
@Order(3)
public class TiingoDataSourceStrategy implements DataSourceStrategy {

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
        return tiingoProperties.hasToken();
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
            // 两日窗口：目标日 + 其前一日（d-3 覆盖周末），脚本层相邻 close 算 changePercent
            LocalDate startDate = tradeDate.minusDays(3);
            log.info("[TiingoDataSourceStrategy] dateRange symbol={}, range=[{},{}]", symbol, startDate, tradeDate);
            String result = pythonScriptExecutor.executeScriptWithEnvironment(
                    apiKeyEnv(), getScriptName(), "get_daily_kline_range",
                    symbol, startDate.toString(), tradeDate.toString());
            if (result != null && result.contains("\"error\"")) {
                String errMsg = extractErrorFromJson(result);
                log.warn("[Tiingo] getDailyKLineDataByDateRange error for {}: {}", symbol, errMsg);
                // P1-3：Python 侧失败 —— 带分类抛出，not-found 才计入黑名单
                throw StockDataException.classify(symbol, "tiingo", errMsg, null);
            }
            // 目标日响应日志：只打印脚本返回中 tradeDate 那一条的完整参数（便于日志分析字段正确性/空值）
            KLineData parsed = parseKLineData(symbol, result);
            if (parsed != null && parsed.getItems() != null && !parsed.getItems().isEmpty()) {
                KLineIterator target = parsed.getItems().stream()
                        .filter(it -> it.getTimeString() != null && it.getTimeString().equals(tradeDate.toString()))
                        .findFirst().orElse(null);
                if (target != null) {
                    log.info("[TiingoDataSourceStrategy] dateRange response target: symbol={}, date={}, json={}",
                            symbol, tradeDate, extractTargetItemJson(result, target.getTimeString()));
                } else {
                    log.info("[TiingoDataSourceStrategy] dateRange response target: symbol={}, date={} NOT FOUND in {} items",
                            symbol, tradeDate, parsed.getItems().size());
                }
            }
            return parsed;
        } catch (StockDataException e) {
            throw e;
        } catch (Exception e) {
            log.warn("tiingo getDailyKLineDataByDateRange failed for {}: {}", symbol, e.getMessage());
            throw new StockDataException(symbol, "tiingo", "获取K线数据失败: " + e.getMessage(),
                    e, StockDataException.ErrorCategory.TRANSIENT_FAILURE);
        }
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
     * 从脚本原始 JSON 中提取目标日 item 的 JSON（保留脚本原始字段/精度）。
     */
    private String extractTargetItemJson(String result, String timeString) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(result);
            for (com.fasterxml.jackson.databind.JsonNode it : root.path("items")) {
                if (timeString.equals(it.path("timeString").asText())) {
                    return it.toString();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
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
