package com.stock.invest.service.impl;

import com.stock.invest.entity.StockDailyBar;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class DataGapFillerServiceImplTest {

    private List<StockDailyBar> barsOf(LocalDate... dates) {
        return Arrays.stream(dates)
                .sorted(Comparator.reverseOrder())
                .map(d -> {
                    StockDailyBar bar = new StockDailyBar();
                    bar.setTradeDate(d);
                    bar.setSymbol("AAPL");
                    return bar;
                })
                .collect(Collectors.toList());
    }

    @Test
    void shouldFindMissingWhenDataStopsDaysAgo() {
        LocalDate today = ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalDate();
        LocalDate stopDate = today.minusDays(5);
        List<StockDailyBar> bars = barsOf(stopDate);
        List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(bars);
        assertFalse(missing.isEmpty(), "should have missing dates");
        for (LocalDate d : missing) {
            assertFalse(d.isBefore(stopDate), d + " should not be before stopDate");
            assertFalse(d.isAfter(today), d + " should not be after today");
        }
    }

    @Test
    void shouldNotLookbackBeyond30Days() {
        LocalDate today = ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalDate();
        LocalDate veryOld = today.minusDays(60);
        List<StockDailyBar> bars = barsOf(veryOld);
        List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(bars);
        LocalDate lookbackLimit = today.minusDays(30);
        for (LocalDate d : missing) {
            assertFalse(d.isBefore(lookbackLimit),
                    () -> d + " should not be before limit " + lookbackLimit);
        }
    }

    @Test
    void shouldReturnEmptyWhenOnlyTodayData() {
        LocalDate today = ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalDate();
        List<StockDailyBar> bars = barsOf(today);
        List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(bars);
        assertTrue(missing.isEmpty(), "no gaps when only today data");
    }

    @Test
    void shouldReturnEmptyWhenEmptyBars() {
        List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(Collections.emptyList());
        assertTrue(missing.isEmpty());
    }

    @Test
    void shouldLimitMaxMissingDates() {
        LocalDate today = ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalDate();
        LocalDate stopDate = today.minusDays(20);
        List<StockDailyBar> bars = barsOf(stopDate);
        List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(bars);
        assertTrue(missing.size() <= 5, "max 5, actual " + missing.size());
    }

    @Test
    void shouldFindGapsInMultiBarData() {
        LocalDate mon = LocalDate.of(2026, 5, 18);
        LocalDate wed = LocalDate.of(2026, 5, 20);
        List<StockDailyBar> bars = barsOf(wed, mon);
        List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(bars);
        assertTrue(missing.contains(LocalDate.of(2026, 5, 19)),
                "should detect Tue 5/19, actual: " + missing);
    }

    @Test
    void shouldSkipWeekends() {
        LocalDate today = ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalDate();
        LocalDate friday = today;
        while (friday.getDayOfWeek() != DayOfWeek.FRIDAY) {
            friday = friday.minusDays(1);
        }
        List<StockDailyBar> bars = barsOf(friday);
        List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(bars);
        for (LocalDate d : missing) {
            assertNotEquals(DayOfWeek.SATURDAY, d.getDayOfWeek(), d + " is Saturday");
            assertNotEquals(DayOfWeek.SUNDAY, d.getDayOfWeek(), d + " is Sunday");
        }
    }

    @Test
    void shouldHandleFutureDataGracefully() {
        LocalDate today = ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalDate();
        LocalDate future = today.plusDays(3);
        List<StockDailyBar> bars = barsOf(future);
        List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(bars);
        assertNotNull(missing, "future data should not cause NPE");
    }
}
