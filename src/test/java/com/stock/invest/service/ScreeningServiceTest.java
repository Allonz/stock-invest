package com.stock.invest.service;

import com.stock.invest.config.ScannerProperties;
import com.stock.invest.entity.ScreeningMatch;
import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.repository.ScreeningMatchRepository;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.impl.ScreeningServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
@DisplayName("ScreeningService — 模式筛选服务测试")
class ScreeningServiceTest {

    @Mock private StockDailyBarRepository stockDailyBarRepository;
    @Mock private ScreeningMatchRepository screeningMatchRepository;
    @Mock private PatternEvaluateService patternEvaluateService;
    @Mock private ScannerProperties scannerProperties;

    @InjectMocks
    private ScreeningServiceImpl screeningService;

    private StockDailyBar bar(String symbol, LocalDate date, double open, double close, long volume, String source) {
        StockDailyBar b = new StockDailyBar();
        b.setSymbol(symbol);
        b.setTradeDate(date);
        b.setOpenPrice(open);
        b.setClosePrice(close);
        b.setVolume(volume);
        b.setSource(source);
        return b;
    }

    @Nested
    @DisplayName("runScreening — 筛选流程")
    class RunScreeningTest {

        @Test
        @DisplayName("正常筛选流程，结果写入 screening_match")
        void test_screening_success() {
            String symbol = "TEST";
            LocalDate tradeDate = LocalDate.of(2026, 5, 18);

            // 生成过去14天的连续数据
            List<StockDailyBar> bars = new ArrayList<>();
            for (int i = 13; i >= 0; i--) {
                LocalDate d = tradeDate.minusDays(i);
                if (d.getDayOfWeek().getValue() <= 5) {
                    bars.add(bar(symbol, d, 0.08, 0.09, 10000L + i * 500L, "tiger"));
                }
            }

            when(patternEvaluateService.matchesIncreasingVolumePattern(anyList(), anyInt()))
                    .thenReturn(true);
            when(stockDailyBarRepository.findByTradeDateBetweenOrderByTradeDateDesc(any(LocalDate.class), eq(tradeDate)))
                    .thenReturn(bars);
            when(scannerProperties.getMinPrice()).thenReturn(0.05);
            when(scannerProperties.getMaxPrice()).thenReturn(0.50);
            when(screeningMatchRepository.saveAll(anyList()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            String batchId = screeningService.runScreening(tradeDate);

            assertNotNull(batchId);
            verify(screeningMatchRepository, times(1)).saveAll(anyList());
        }

        @Test
        @DisplayName("数据不足7天跳过")
        void test_screening_insufficientData() {
            String symbol = "SHORT";
            LocalDate tradeDate = LocalDate.of(2026, 5, 18);

            // 只有2天数据
            List<StockDailyBar> bars = new ArrayList<>();
            bars.add(bar(symbol, tradeDate.minusDays(1), 0.10, 0.11, 5000L, "tiger"));
            bars.add(bar(symbol, tradeDate, 0.11, 0.12, 6000L, "tiger"));

            when(stockDailyBarRepository.findByTradeDateBetweenOrderByTradeDateDesc(any(LocalDate.class), eq(tradeDate)))
                    .thenReturn(bars);

            String batchId = screeningService.runScreening(tradeDate);

            // 数据不足7天，但内部取 windowDays = min(7, 2) = 2 < 3，所以被跳过
            verify(screeningMatchRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("价格超出范围跳过（$0.50 上限）")
        void test_screening_priceFilter() {
            String symbol = "HPRC";
            LocalDate tradeDate = LocalDate.of(2026, 5, 18);

            List<StockDailyBar> bars = new ArrayList<>();
            for (int i = 13; i >= 0; i--) {
                LocalDate d = tradeDate.minusDays(i);
                if (d.getDayOfWeek().getValue() <= 5) {
                    // closePrice = 0.80，超出 0.50 上限
                    bars.add(bar(symbol, d, 0.78, 0.80, 10000L + i * 500L, "tiger"));
                }
            }

            when(stockDailyBarRepository.findByTradeDateBetweenOrderByTradeDateDesc(any(LocalDate.class), eq(tradeDate)))
                    .thenReturn(bars);
            when(scannerProperties.getMinPrice()).thenReturn(0.05);
            when(scannerProperties.getMaxPrice()).thenReturn(0.50);

            String batchId = screeningService.runScreening(tradeDate);

            verify(screeningMatchRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("无数据时返回空 batchId")
        void test_noData() {
            LocalDate tradeDate = LocalDate.of(2026, 5, 18);

            when(stockDailyBarRepository.findByTradeDateBetweenOrderByTradeDateDesc(any(LocalDate.class), eq(tradeDate)))
                    .thenReturn(List.of());

            String batchId = screeningService.runScreening(tradeDate);
            assertNotNull(batchId);
            verify(screeningMatchRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("模式不匹配时跳过")
        void test_patternNotMatch() {
            String symbol = "NOPAT";
            LocalDate tradeDate = LocalDate.of(2026, 5, 18);

            List<StockDailyBar> bars = new ArrayList<>();
            for (int i = 13; i >= 0; i--) {
                LocalDate d = tradeDate.minusDays(i);
                if (d.getDayOfWeek().getValue() <= 5) {
                    bars.add(bar(symbol, d, 0.08, 0.09, 10000L + i * 500L, "tiger"));
                }
            }

            when(stockDailyBarRepository.findByTradeDateBetweenOrderByTradeDateDesc(any(LocalDate.class), eq(tradeDate)))
                    .thenReturn(bars);
            when(scannerProperties.getMinPrice()).thenReturn(0.05);
            when(scannerProperties.getMaxPrice()).thenReturn(0.50);
            when(patternEvaluateService.matchesIncreasingVolumePattern(anyList(), anyInt()))
                    .thenReturn(false);

            String batchId = screeningService.runScreening(tradeDate);

            verify(screeningMatchRepository, never()).saveAll(anyList());
        }
    }
}
