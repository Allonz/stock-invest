package com.stock.invest.support;

import com.stock.invest.entity.DataFillTask;
import com.stock.invest.entity.ScreeningMatch;
import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 测试数据工厂：生成 mock 的实体和模型对象。
 */
public class TestDataFactory {

    private TestDataFactory() {}

    // ========== StockDailyBar ==========

    public static StockDailyBar createStockDailyBar(String symbol, LocalDate tradeDate,
                                                     double open, double close, long volume, String source) {
        StockDailyBar bar = new StockDailyBar();
        bar.setSymbol(symbol);
        bar.setTradeDate(tradeDate);
        bar.setOpenPrice(open);
        bar.setClosePrice(close);
        bar.setVolume(volume);
        bar.setSource(source);
        return bar;
    }

    public static StockDailyBar createStockDailyBar(Long id, String symbol, LocalDate tradeDate,
                                                     double open, double close, long volume, String source) {
        StockDailyBar bar = createStockDailyBar(symbol, tradeDate, open, close, volume, source);
        bar.setId(id);
        return bar;
    }

    /**
     * 生成从 startDate 开始的连续交易日序列（跳过周末），每天的 close 和 volume 递增。
     */
    public static List<StockDailyBar> createContinuousBars(String symbol, LocalDate startDate,
                                                            int count, double basePrice, long baseVolume, String source) {
        List<StockDailyBar> bars = new ArrayList<>();
        LocalDate cursor = startDate;
        int idx = 0;
        while (bars.size() < count) {
            if (cursor.getDayOfWeek().getValue() <= 5) {
                bars.add(createStockDailyBar(symbol, cursor,
                        basePrice + idx, basePrice + idx,
                        baseVolume + idx * 1000L, source));
                idx++;
            }
            cursor = cursor.plusDays(1);
        }
        return bars;
    }

    /**
     * 生成带日期跳空的 bars（中间缺少某些交易日）。
     */
    public static List<StockDailyBar> createBarsWithGaps(String symbol, LocalDate[] dates,
                                                          double basePrice, long baseVolume, String source) {
        List<StockDailyBar> bars = new ArrayList<>();
        for (int i = 0; i < dates.length; i++) {
            bars.add(createStockDailyBar(symbol, dates[i],
                    basePrice + i, basePrice + i,
                    baseVolume + i * 1000L, source));
        }
        return bars;
    }

    // ========== ScreeningMatch ==========

    public static ScreeningMatch createScreeningMatch(String batchId, String symbol, LocalDate tradeDate,
                                                       double price, boolean rise, String dataSource) {
        ScreeningMatch match = new ScreeningMatch();
        match.setBatchId(batchId);
        match.setSymbol(symbol);
        match.setTradeDate(tradeDate);
        match.setPrice(price);
        match.setLastClose(price);
        match.setRise(rise);
        match.setDataSource(dataSource);
        return match;
    }

    // ========== DataFillTask ==========

    public static DataFillTask createDataFillTask(String symbol, LocalDate tradeDate,
                                                   String status, int retryCount, int dayCount) {
        DataFillTask task = new DataFillTask();
        task.setSymbol(symbol);
        task.setTradeDate(tradeDate);
        task.setStatus(status);
        task.setRetryCount(retryCount);
        task.setDayCount(dayCount);
        task.setCreatedAt(Instant.now());
        return task;
    }

    // ========== KLineData / KLineIterator ==========

    public static KLineData createKLineData(String symbol, LocalDate tradeDate,
                                             double open, double close, long volume) {
        long epochMillis = tradeDate.atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli();
        KLineData kd = new KLineData();
        kd.setSymbol(symbol);
        KLineIterator item = new KLineIterator(symbol, epochMillis, open, close + 1, close - 1, close, volume, 0);
        kd.addItem(item);
        return kd;
    }

    /**
     * 创建包含指定日期列表的 KLineData（每个日期一个 KLineIterator）。
     */
    public static KLineData createKLineDataWithDates(String symbol, List<LocalDate> dates,
                                                      double basePrice, long baseVolume) {
        KLineData kd = new KLineData();
        kd.setSymbol(symbol);
        for (int i = 0; i < dates.size(); i++) {
            LocalDate d = dates.get(i);
            long epochMillis = d.atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli();
            KLineIterator item = new KLineIterator(symbol, epochMillis,
                    basePrice + i, basePrice + i + 1, basePrice + i - 1,
                    basePrice + i, baseVolume + i * 1000L, 0);
            kd.addItem(item);
        }
        return kd;
    }
}
