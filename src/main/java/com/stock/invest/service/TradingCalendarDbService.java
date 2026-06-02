package com.stock.invest.service;

import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stock.invest.entity.TradingCalendarEntity;
import com.stock.invest.model.TradingCalendarResult;
import com.stock.invest.repository.TradingCalendarRepository;
import com.stock.invest.service.impl.TradingCalendarFallback;

/**
 * 交易日历持久化服务。
 *
 * 职责：
 * 1. 优先从 trading_calendar 表查询，无记录则 fallback 到 TradingCalendarFallback 实时查并入库
 * 2. 支持整年日历批量抓取（逐天 upsert）
 * 3. 所有日期使用 America/New_York 时区
 */
@Service
public class TradingCalendarDbService {

    private static final Logger log = LoggerFactory.getLogger(TradingCalendarDbService.class);

    private final TradingCalendarRepository repository;
    private final TradingCalendarFallback fallback;

    public TradingCalendarDbService(TradingCalendarRepository repository,
                                    TradingCalendarFallback fallback) {
        this.repository = repository;
        this.fallback = fallback;
    }

    /**
     * 查询单日是否为交易日。
     * 策略：DB 优先 → fallback 链实时查并入库。
     *
     * @param market 市场代码，如 "US"
     * @param date    美东日期
     * @return true=交易日，false=非交易日，null=所有数据源不可用
     */
    @Transactional
    public Boolean isTradingDay(String market, LocalDate date) {
        // 1. 查 DB
        Optional<TradingCalendarEntity> cached = repository.findByMarketAndTradeDate(market, date);
        if (cached.isPresent()) {
            log.debug("[TradingCalendarDbService] DB 命中: {}-{} -> isOpen={}", market, date, cached.get().getIsOpen());
            return cached.get().getIsOpen();
        }

        // 2. DB 无记录 → fallback 链实时查
        TradingCalendarResult result = fallback.isTradingDay(market, date);
        if (result != null) {
            // 3. 结果入库
            upsert(market, date, result.isTradingDay(), result.getSource(),
                   result.getType(), result.getDetail());
            return result.isTradingDay();
        }

        // 4. 数据源也查不到 → 返回 null，由调用方决定默认值
        log.warn("[TradingCalendarDbService] 所有数据源不可用: market={}, date={}", market, date);
        return null;
    }

    /**
     * 抓取指定市场指定年份的完整开盘日历。
     * 逐天通过 fallback 链查询后 upsert 入库。
     *
     * @param market 市场代码
     * @param year   年份
     * @return 成功 upsert 的记录数
     */
    @Transactional
    public int fetchAndStoreFullYear(String market, int year) {
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);

        int count = 0;
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            TradingCalendarResult result = fallback.isTradingDay(market, cursor);
            if (result != null) {
                int affected = upsert(market, cursor, result.isTradingDay(),
                        result.getSource(), result.getType(), result.getDetail());
                count += affected;
            } else {
                log.warn("[TradingCalendarDbService] fetchFullYear: 数据源不可用, 跳过 date={}", cursor);
            }
            cursor = cursor.plusDays(1);
        }

        log.info("[TradingCalendarDbService] fetchAndStoreFullYear 完成: market={}, year={}, count={}", market, year, count);
        return count;
    }

    /**
     * 获取某年整年日历列表（仅查 DB，不触发外部调用）。
     */
    public List<TradingCalendarEntity> getYearCalendar(String market, int year) {
        return repository.findByMarketAndYear(market, year);
    }

    /**
     * 获取指定日期范围内的日历记录。
     */
    public List<TradingCalendarEntity> getRange(String market, LocalDate start, LocalDate end) {
        return repository.findByMarketAndTradeDateBetween(market, start, end);
    }

    /**
     * 判断指定年份的日历数据是否已在 DB 中完整存在（365/366 天）。
     */
    public boolean isYearCompleteInDb(String market, int year) {
        List<TradingCalendarEntity> existing = repository.findByMarketAndYear(market, year);
        int expectedDays = Year.isLeap(year) ? 366 : 365;
        return existing.size() >= expectedDays;
    }

    // ---- 内部方法 ----

    private int upsert(String market, LocalDate date, boolean isOpen,
                       String source, String type, String detail) {
        return repository.upsert(market, date, isOpen, source, type, detail);
    }
}
