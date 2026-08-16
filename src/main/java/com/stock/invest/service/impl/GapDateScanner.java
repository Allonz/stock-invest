package com.stock.invest.service.impl;

import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.service.TradingCalendarDbService;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 补缺日期扫描器。
 *
 * <p>负责在给定现有 K 线记录和交易日历的情况下，计算需要补缺的缺失交易日。</p>
 */
final class GapDateScanner {

    private static final ZoneId AMERICA_NY = ZoneId.of("America/New_York");
    private static final int MAX_LOOKBACK_DAYS = 7;
    private static final int MAX_MISSING_DATES_PER_SYMBOL = 5;

    private GapDateScanner() {
    }

    /**
     * 计算 [max(oldestBar, today-30d), today(NY)] 范围内的缺失交易日。
     */
    static List<LocalDate> findMissingTradeDates(List<StockDailyBar> existingBars,
                                                 TradingCalendarDbService calendarDbService) {
        if (existingBars == null || existingBars.isEmpty()) {
            return Collections.emptyList();
        }

        // 显式升序排序，消除对调用方顺序的隐式依赖
        List<StockDailyBar> sorted = existingBars.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(StockDailyBar::getTradeDate))
                .toList();
        LocalDate newestInBars = sorted.get(sorted.size() - 1).getTradeDate();
        LocalDate oldestInBars = sorted.get(0).getTradeDate();

        // 以纽约时间为基准的"今天"
        LocalDate today = ZonedDateTime.now(AMERICA_NY).toLocalDate();

        // 只考察最近 MAX_LOOKBACK_DAYS 天
        LocalDate lookbackLimit = today.minusDays(MAX_LOOKBACK_DAYS);
        LocalDate rangeStart = oldestInBars.isAfter(lookbackLimit) ? oldestInBars : lookbackLimit;

        // 范围上界：00:00~16:00 ET 排除当天，16:00~23:59 ET 包含当天
        LocalTime nowTime = LocalTime.now(AMERICA_NY);
        LocalDate rangeEnd;
        if (nowTime.isBefore(LocalTime.of(16, 0))) {
            LocalDate yesterday = today.minusDays(1);
            rangeEnd = newestInBars.isAfter(yesterday) ? newestInBars : yesterday;
        } else {
            rangeEnd = newestInBars.isAfter(today) ? newestInBars : today;
        }

        Set<LocalDate> existingDates = sorted.stream()
                .map(StockDailyBar::getTradeDate)
                .collect(Collectors.toSet());

        List<LocalDate> missing = new ArrayList<>();
        LocalDate cursor = rangeStart;
        while (!cursor.isAfter(rangeEnd)) {
            if (cursor.getDayOfWeek().getValue() <= 5) {
                if (calendarDbService != null) {
                    Boolean isOpen = calendarDbService.isTradingDay("US", cursor);
                    if (isOpen == null) {
                        cursor = cursor.plusDays(1);
                        continue;
                    }
                    if (Boolean.FALSE.equals(isOpen)) {
                        cursor = cursor.plusDays(1);
                        continue;
                    }
                }
                if (!existingDates.contains(cursor)) {
                    missing.add(cursor);
                }
            }
            cursor = cursor.plusDays(1);
        }

        if (missing.size() > MAX_MISSING_DATES_PER_SYMBOL) {
            return missing.subList(missing.size() - MAX_MISSING_DATES_PER_SYMBOL, missing.size());
        }
        return missing;
    }
}
