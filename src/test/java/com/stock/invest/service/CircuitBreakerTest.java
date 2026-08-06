package com.stock.invest.service;

import com.stock.invest.config.GapFillProperties;
import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.exception.StockDataException;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
import com.stock.invest.repository.DataFillTaskRepository;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.impl.DataGapFillerServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P1-5：账户级错误（权限/配额）→ 源级熔断 30 分钟。
 * <p>
 * 熔断器内嵌于 {@link DataGapFillerServiceImpl}（sourceCooldownUntil 冷却表 +
 * isSourceCooledDown + SOURCE_COOLDOWN_MILLIS=30min），无独立组件，
 * 本类通过服务行为 + 反射断言冷却表。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("P1-5 Tiger 配额熔断（内嵌于 DataGapFillerServiceImpl）")
class CircuitBreakerTest {

    private static final ZoneId AMERICA_NY = ZoneId.of("America/New_York");

    @Mock private StockDailyBarRepository stockDailyBarRepository;
    @Mock private DataFillTaskRepository dataFillTaskRepository;
    @Mock private DataSourceStrategy tigerSource;
    @Mock private DataSourceStrategy yfinanceSource;
    @Mock private DataSourceStrategy twelvedataSource;
    @Mock private DataSourceStrategy tiingoSource;
    @Mock private GapFillProperties gapFillProperties;
    @Mock private DataFillProgressService dataFillProgressService;
    @Mock private RetryProgressService retryProgressService;
    @Mock private TradingCalendarDbService tradingCalendarDbService;
    @Mock private StockDataSourcePriorityService stockDataSourcePriorityService;
    @Mock private SymbolBlacklistService symbolBlacklistService;
    @Mock private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private DataGapFillerServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(tigerSource.getSourceName()).thenReturn("tiger");
        lenient().when(tigerSource.isAvailable()).thenReturn(true);
        lenient().when(yfinanceSource.getSourceName()).thenReturn("yfinance");
        lenient().when(yfinanceSource.isAvailable()).thenReturn(true);
        lenient().when(twelvedataSource.getSourceName()).thenReturn("twelvedata");
        lenient().when(twelvedataSource.isAvailable()).thenReturn(true);
        lenient().when(tiingoSource.getSourceName()).thenReturn("tiingo");
        lenient().when(tiingoSource.isAvailable()).thenReturn(true);

        lenient().when(gapFillProperties.getMinPriceThreshold()).thenReturn(1.0);
        lenient().when(stockDataSourcePriorityService.getPriorityList(anyString()))
                .thenReturn(List.of("tiger", "yfinance", "twelvedata", "tiingo"));

        KLineData mismatch = new KLineData();
        mismatch.setItems(List.of(new KLineIterator("X", 0L, 0, 0, 0, 0, 0, 0, 0, 0, 0)));
        lenient().when(yfinanceSource.getDailyKLineDataByDateRange(anyString(), any())).thenReturn(mismatch);
        lenient().when(twelvedataSource.getDailyKLineDataByDateRange(anyString(), any())).thenReturn(mismatch);
        lenient().when(tiingoSource.getDailyKLineDataByDateRange(anyString(), any())).thenReturn(mismatch);

        service = new DataGapFillerServiceImpl(
                stockDailyBarRepository, dataFillTaskRepository,
                List.of(tigerSource, yfinanceSource, twelvedataSource, tiingoSource),
                gapFillProperties, dataFillProgressService, retryProgressService, tradingCalendarDbService,
                stockDataSourcePriorityService, symbolBlacklistService, transactionManager);
    }

    private LocalDate nyToday() {
        return ZonedDateTime.now(AMERICA_NY).toLocalDate();
    }

    /** 单 symbol + 单缺失日期（日历仅开放 probeDate）—— 每个 symbol 恰好触发一次 fetch。 */
    private void stubSymbolWithSingleGap(String symbol, LocalDate probeDate) {
        when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(eq(symbol), any()))
                .thenReturn(new ArrayList<>(List.of(lowBar(symbol, probeDate.minusDays(1)))));
    }

    private static StockDailyBar lowBar(String symbol, LocalDate tradeDate) {
        StockDailyBar b = new StockDailyBar();
        b.setSymbol(symbol);
        b.setTradeDate(tradeDate);
        b.setOpenPrice(0.5);
        b.setClosePrice(0.5);
        b.setVolume(10L);
        b.setSource("yfinance");
        return b;
    }

    private static LocalDate probeDate(LocalDate today) {
        LocalDate d = today.minusDays(9);
        while (d.getDayOfWeek().getValue() > 5) {
            d = d.minusDays(1);
        }
        return d;
    }

    private void openCalendarOnlyOn(LocalDate openDay) {
        when(tradingCalendarDbService.isTradingDay(anyString(), any(LocalDate.class)))
                .thenAnswer(inv -> openDay.equals(inv.getArgument(1, LocalDate.class)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> cooldownMap() throws Exception {
        Field f = DataGapFillerServiceImpl.class.getDeclaredField("sourceCooldownUntil");
        f.setAccessible(true);
        return (Map<String, Long>) f.get(service);
    }

    // ---- 1. 错误分类 ----

    @Test
    @DisplayName("P1-5: 4000/permission/quota 消息分类为 ACCOUNT_LEVEL")
    void permissionDeniedClassifiedAsAccountLevel() {
        assertEquals(StockDataException.ErrorCategory.ACCOUNT_LEVEL,
                StockDataException.classify("AAPL", "tiger", "4000:permission denied", null).getCategory());
        assertEquals(StockDataException.ErrorCategory.ACCOUNT_LEVEL,
                StockDataException.classify("AAPL", "tiger", "quota exceeded", null).getCategory());
        assertEquals(StockDataException.ErrorCategory.ACCOUNT_LEVEL,
                StockDataException.classify("AAPL", "tiger", "无权限访问该接口", null).getCategory());
        assertEquals(StockDataException.ErrorCategory.CONFIRMED_NOT_FOUND,
                StockDataException.classify("AAPL", "tiger", "symbol not found", null).getCategory());
        assertEquals(StockDataException.ErrorCategory.TRANSIENT_FAILURE,
                StockDataException.classify("AAPL", "tiger", "connect timeout", null).getCategory());
    }

    // ---- 2. 链终止 ----

    @Test
    @DisplayName("P1-5: 账户级错误终止本 symbol 的 fallback 链")
    void accountLevelAbortsFallbackChain() {
        LocalDate today = nyToday();
        LocalDate day = probeDate(today);
        openCalendarOnlyOn(day);
        when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of("AAPL"));
        stubSymbolWithSingleGap("AAPL", day);

        when(tigerSource.getDailyKLineDataByDateRange(eq("AAPL"), any()))
                .thenThrow(new StockDataException("AAPL", "tiger", "4000:permission denied",
                        StockDataException.ErrorCategory.ACCOUNT_LEVEL));

        service.fillGaps();

        // 链在 tiger 处终止：其余源一次都不应被调用
        verify(tigerSource, atLeastOnce()).getDailyKLineDataByDateRange(eq("AAPL"), any());
        verify(yfinanceSource, never()).getDailyKLineDataByDateRange(anyString(), any());
        verify(twelvedataSource, never()).getDailyKLineDataByDateRange(anyString(), any());
        verify(tiingoSource, never()).getDailyKLineDataByDateRange(anyString(), any());
        // 账户级错误不是 not-found：不入黑名单
        verify(symbolBlacklistService, never()).recordNotFound(anyString(), anyMap());
    }

    // ---- 3. 冷却 30 分钟 ----

    @Test
    @DisplayName("P1-5: 账户级错误后熔断 30 分钟（冷却表记录 now+30min）")
    void circuitOpensFor30Minutes() throws Exception {
        LocalDate today = nyToday();
        LocalDate day = probeDate(today);
        openCalendarOnlyOn(day);
        when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of("AAPL"));
        stubSymbolWithSingleGap("AAPL", day);

        when(tigerSource.getDailyKLineDataByDateRange(eq("AAPL"), any()))
                .thenThrow(new StockDataException("AAPL", "tiger", "4000:permission denied",
                        StockDataException.ErrorCategory.ACCOUNT_LEVEL));

        service.fillGaps();

        Long until = cooldownMap().get("tiger");
        assertNotNull(until, "tiger should be in cooldown table after account-level error");
        long now = System.currentTimeMillis();
        assertTrue(until >= now + 30 * 60 * 1000L - 1000L,
                "cooldown until should be ~30min from now, until=" + until + ", now=" + now);
        assertTrue(until <= now + 30 * 60 * 1000L + 5000L, "cooldown must not exceed 30min + slack");
    }

    // ---- 4. 批次内跳过 ----

    @Test
    @DisplayName("P1-5: 熔断生效后批次内后续 symbol 的链不再包含 tiger")
    void sourceSkippedForBatch() {
        LocalDate today = nyToday();
        LocalDate day = probeDate(today);
        openCalendarOnlyOn(day);
        when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of("S1", "S2"));
        stubSymbolWithSingleGap("S1", day);
        stubSymbolWithSingleGap("S2", day);

        when(tigerSource.getDailyKLineDataByDateRange(eq("S1"), any()))
                .thenThrow(new StockDataException("S1", "tiger", "4000:permission denied",
                        StockDataException.ErrorCategory.ACCOUNT_LEVEL));

        service.fillGaps();

        // S1 触发熔断；S2 的 fallback 链跳过 tiger（tiger 总共只被 S1 调用一次）
        verify(tigerSource, times(1)).getDailyKLineDataByDateRange(anyString(), any());
        // S2 走 yfinance（日期不匹配 → retryableFailure → retry 任务）
        verify(yfinanceSource, atLeastOnce()).getDailyKLineDataByDateRange(eq("S2"), any());
    }

    // ---- 5. 冷却过期恢复 ----

    @Test
    @DisplayName("P1-5: 冷却期过后该源重新进入 fallback 链")
    void cooldownExpiryRestoresSource() throws Exception {
        LocalDate today = nyToday();
        LocalDate day = probeDate(today);
        openCalendarOnlyOn(day);
        when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of("AAPL"));
        stubSymbolWithSingleGap("AAPL", day);

        when(tigerSource.getDailyKLineDataByDateRange(eq("AAPL"), any()))
                .thenThrow(new StockDataException("AAPL", "tiger", "4000:permission denied",
                        StockDataException.ErrorCategory.ACCOUNT_LEVEL));

        service.fillGaps();
        assertNotNull(cooldownMap().get("tiger"), "cooldown should be active after first run");

        // 时间推进：冷却表条目置为过去 → 过期后源恢复
        cooldownMap().put("tiger", System.currentTimeMillis() - 1000L);

        service.fillGaps();

        // 恢复后 tiger 再次被调用（第二次 fillGaps 中）
        verify(tigerSource, times(2)).getDailyKLineDataByDateRange(eq("AAPL"), any());
    }
}
