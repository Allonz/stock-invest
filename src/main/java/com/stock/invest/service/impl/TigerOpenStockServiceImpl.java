package com.stock.invest.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.client.TigerOpenPythonBridge;
import com.stock.invest.exception.StockDataException;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
import com.stock.invest.service.DataSourceStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import org.springframework.core.annotation.Order;

/**
 * 基于 Tiger OpenAPI (Python SDK) 桥接的数据源实现。
 * <p>
 * 替代原 {@link TigerPythonService}，注入 {@link TigerOpenPythonBridge} 实现
 * {@link DataSourceStrategy} 接口，作为 fallback 链的第二级（优先级 "tigeropen"）。
 * </p>
 */
@Service("tigerOpenStockService")
@Order(4)
public class TigerOpenStockServiceImpl implements DataSourceStrategy {

    private static final Logger log = LoggerFactory.getLogger(TigerOpenStockServiceImpl.class);

    private final TigerOpenPythonBridge bridge;
    private final ObjectMapper objectMapper;

    public TigerOpenStockServiceImpl(TigerOpenPythonBridge bridge,
                                     ObjectMapper objectMapper) {
        this.bridge = bridge;
        this.objectMapper = objectMapper;
        log.info("TigerOpenStockServiceImpl: Service initialized (available={})", isAvailable());
    }

    @Override
    public String getSourceName() {
        return "tigeropen";
    }

    @Override
    public boolean isAvailable() {
        return bridge.hasCredentials();
    }

@Override
    public KLineData getDailyKLineDataAsObject(String symbol) {
        try {
            return bridge.fetchDailyBars(symbol, 12);
        } catch (Exception e) {
            log.warn("[TigerOpenStock] getDailyKLineDataAsObject failed for {}: {}", symbol, e.getMessage());
            return new KLineData();
        }
    }

    /**
     * P1-3/P1-5：补缺 fallback 链入口 —— 失败时抛带分类的 {@link StockDataException}，
     * 账户级错误（4000/permission/quota）触发源级熔断，not-found 才计入黑名单；
     * 不再像 {@link #getDailyKLineDataAsObject} 那样返回空 KLineData 被误判为"不存在"。
     */
    @Override
    public KLineData getDailyKLineDataByDateRange(String symbol, LocalDate tradeDate) {
        try {
            // 两日窗口：目标日 + 其前一日（d-3 覆盖周末），脚本层相邻 close 算 changePercent
            KLineData data = bridge.fetchDailyBarsByRange(symbol,
                    tradeDate.minusDays(3).toString(), tradeDate.toString());
            if (data == null) {
                throw new StockDataException(symbol, "tigeropen", "无数据返回",
                        StockDataException.ErrorCategory.TRANSIENT_FAILURE);
            }
            // 目标日响应日志：只打印脚本返回中 tradeDate 那一条的完整参数（便于日志分析字段正确性/空值）
            // tigeropen item.timeString 为空 → 用 epoch 毫秒按美东时区匹配目标日
            KLineIterator target = null;
            if (data != null && data.getItems() != null) {
                for (KLineIterator it : data.getItems()) {
                    LocalDate itemDate = it.getTimeString() != null && !it.getTimeString().isEmpty()
                            ? LocalDate.parse(it.getTimeString())
                            : java.time.Instant.ofEpochMilli(it.getTime())
                                    .atZone(java.time.ZoneId.of("America/New_York")).toLocalDate();
                    if (itemDate.equals(tradeDate)) {
                        target = it;
                        break;
                    }
                }
            }
            try {
                if (target != null) {
                    log.info("[TigerOpenStock] dateRange response target: symbol={}, date={}, json={}",
                            symbol, tradeDate, objectMapper.writeValueAsString(target));
                } else {
                    log.info("[TigerOpenStock] dateRange response target: symbol={}, date={} NOT FOUND in {} items",
                            symbol, tradeDate, data.getItems() == null ? 0 : data.getItems().size());
                }
            } catch (Exception logEx) {
                log.warn("[TigerOpenStock] response target serialization failed: {}", logEx.getMessage());
            }
            return data;
        } catch (StockDataException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[TigerOpenStock] getDailyKLineDataByDateRange failed for {}: {}", symbol, e.getMessage());
            throw StockDataException.classify(symbol, "tigeropen", e.getMessage(), e);
        }
    }


    /**
     * 盘后 K 线：经 tigeropen_channel.py 的 afterhours_bars 命令获取。
     * 返回最近 N 根盘后分钟聚合数据，由调用方按交易日匹配目标日期。
     */
    @Override
    public KLineData getAfterHoursKLineDataByDateRange(String symbol, LocalDate tradeDate) {
        try {
            KLineData data = bridge.fetchAfterHoursBars(symbol, 100);
            if (data == null) {
                return new KLineData();
            }
            return data;
        } catch (Exception e) {
            log.warn("[TigerOpenStock] getAfterHoursKLineDataByDateRange failed for {}: {}", symbol, e.getMessage());
            return new KLineData();
        }
    }

}
