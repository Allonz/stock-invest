package com.stock.invest.scheduler;

import com.stock.invest.service.TradingCalendarDbService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneId;

/**
 * 开盘日历定时同步调度。
 * 每周一 4:30 美东时间执行，抓取当年整年开盘日历并 upsert 入库。
 */
@Component
public class TradingCalendarScheduler {

    private static final Logger log = LoggerFactory.getLogger(TradingCalendarScheduler.class);

    private static final ZoneId NY_ZONE = ZoneId.of("America/New_York");
    private static final String DEFAULT_MARKET = "US";

    private final TradingCalendarDbService tradingCalendarDbService;

    public TradingCalendarScheduler(TradingCalendarDbService tradingCalendarDbService) {
        this.tradingCalendarDbService = tradingCalendarDbService;
    }

    /**
     * 每周一 4:30 美东时间执行，查当年整年开盘日历并 upsert 入库。
     */
    @Scheduled(cron = "0 30 4 * * MON", zone = "America/New_York")
    public void fetchWeeklyCalendar() {
        Instant start = Instant.now();
        int currentYear = Year.now(NY_ZONE).getValue();
        log.info("[TradingCalendarScheduler] fetchWeeklyCalendar: === START === year={}, market={}", currentYear, DEFAULT_MARKET);

        try {
            int count = tradingCalendarDbService.fetchAndStoreFullYear(DEFAULT_MARKET, currentYear);
            log.info("[TradingCalendarScheduler] fetchWeeklyCalendar: === END === " +
                    "year={}, count={}, elapsedMs={}",
                    currentYear, count, Duration.between(start, Instant.now()).toMillis());
        } catch (Exception e) {
            log.error("[TradingCalendarScheduler] fetchWeeklyCalendar: === FAILED === " +
                    "year={}, elapsedMs={}, error={}",
                    currentYear, Duration.between(start, Instant.now()).toMillis(), e.getMessage(), e);
        }
    }
}
