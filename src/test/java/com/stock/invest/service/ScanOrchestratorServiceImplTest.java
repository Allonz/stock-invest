package com.stock.invest.service;

import com.stock.invest.config.ScannerProperties;
import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.enums.dto.ScreenerRunResponseDto;
import com.stock.invest.enums.dto.ScreeningMatchProjection;
import com.stock.invest.repository.ScreeningMatchRepository;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.impl.ScanOrchestratorServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ScanOrchestratorServiceImplTest {

    @Mock(lenient = true)
    private ScannerProperties scannerProperties;

    private PatternEvaluateService patternEvaluateService;

    @Mock(lenient = true)
    private ScreeningMatchRepository screeningMatchRepository;

    @Mock(lenient = true)
    private StockDailyBarRepository stockDailyBarRepository;

    @Mock(lenient = true)
    private Executor scanExecutor;

    private ScanOrchestratorServiceImpl service;

    @BeforeEach
    public void setUp() {
        // 使用立即执行任务的 Executor，使 CompletableFuture 可以正常完成
        service = new ScanOrchestratorServiceImpl(
                scannerProperties,
                patternEvaluateService,
                screeningMatchRepository,
                stockDailyBarRepository);

        // 设置默认配置
        when(scannerProperties.getMinPrice()).thenReturn(0.05D);
        when(scannerProperties.getMaxPrice()).thenReturn(0.2D);
        when(scannerProperties.getMaxCandidates()).thenReturn(200);
    }

    @Test
    public void testQueryByDate_ShouldReturnResultsFromRepository() {
        LocalDate testDate = LocalDate.of(2026, 5, 4);
        ScreeningMatchProjection match1 = createProjection("AAA", testDate, 0.15);
        ScreeningMatchProjection match2 = createProjection("BBB", testDate, 0.18);

        when(scannerProperties.getMinPrice()).thenReturn(0.05D);
        when(scannerProperties.getMaxPrice()).thenReturn(0.2D);
        when(screeningMatchRepository.findProjectedByTradeDateAndPriceBetweenOrderByPriceDesc(
                eq(testDate), eq(0.05D), eq(0.2D)))
                .thenReturn(Arrays.asList(match1, match2));

        List<com.stock.invest.enums.dto.ScreeningResultDto> results =
                service.queryByDate(testDate, null, null);

        assertNotNull(results);
        assertEquals(2, results.size());
    }
    @Test
    public void testQueryLatest_ShouldReturnEmptyWhenNoData() {
        when(screeningMatchRepository.findTopByOrderByTradeDateDescIdDesc())
                .thenReturn(Optional.empty());

        List<com.stock.invest.enums.dto.ScreeningResultDto> results = service.queryLatest(null, null);

        assertNotNull(results);
        assertEquals(0, results.size());
    }

    // 辅助方法
    private StockDailyBar createBar(String symbol, LocalDate date, double close, long volume) {
        StockDailyBar bar = new StockDailyBar();
        bar.setSymbol(symbol);
        bar.setTradeDate(date);
        bar.setOpenPrice(close - 0.01);
        bar.setClosePrice(close);
        bar.setVolume(volume);
        bar.setSource("test");
        bar.setCreatedAt(Instant.now());
        return bar;
    }

    private ScreeningMatchProjection createProjection(String symbol, LocalDate date, double price) {
        ScreeningMatchProjection projection = mock(ScreeningMatchProjection.class);
        when(projection.getSymbol()).thenReturn(symbol);
        when(projection.getTradeDate()).thenReturn(date);
        when(projection.getPrice()).thenReturn(price);
        when(projection.getDataSource()).thenReturn("test");
        when(projection.getRise()).thenReturn(true);
        return projection;
    }
}