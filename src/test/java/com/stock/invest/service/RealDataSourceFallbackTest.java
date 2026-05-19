package com.stock.invest.service;

import com.stock.invest.client.TigerOpenPythonBridge;
import com.stock.invest.client.TiingoRestClient;
import com.stock.invest.client.TwelveDataRestClient;
import com.stock.invest.client.YahooFinanceRestClient;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
import com.stock.invest.service.impl.MarketDataSourceRouterImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 第二轮验证测试 —— 真实数据源 Fallback 测试。
 * <p>
 * 数据源客户端使用 Mock，验证 MarketDataSourceRouter 的 fallback 链：
 * Tiger → YFinance → TwelveData → Tiingo 的切换逻辑。
 */
@Tag("round2")
@ExtendWith(MockitoExtension.class)
class RealDataSourceFallbackTest {

    @Mock private TigerOpenPythonBridge tigerOpenPythonBridge;
    @Mock private YahooFinanceRestClient yahooFinanceRestClient;
    @Mock private TwelveDataRestClient twelveDataRestClient;
    @Mock private TiingoRestClient tiingoRestClient;

    @InjectMocks
    private MarketDataSourceRouterImpl marketDataSourceRouter;

    private static final String TEST_SYMBOL = "TEST";
    private static final LocalDate TEST_DATE = LocalDate.of(2026, 5, 18);
    private static final String BARS_RANGE = "1mo";

    private KLineData createMockKLineData(String symbol) {
        KLineData data = new KLineData();
        data.setSymbol(symbol);
        KLineIterator item = new KLineIterator();
        item.setSymbol(symbol);
        item.setTime(TEST_DATE.toEpochDay() * 86400L);
        item.setOpen(0.25);
        item.setHigh(0.30);
        item.setLow(0.20);
        item.setClose(0.28);
        item.setVolume(500000L);
        data.setItems(List.of(item));
        return data;
    }

    private KLineData createEmptyKLineData(String symbol) {
        KLineData data = new KLineData();
        data.setSymbol(symbol);
        data.setItems(List.of());
        return data;
    }

    @BeforeEach
    void setUp() {
        reset(tigerOpenPythonBridge, yahooFinanceRestClient, twelveDataRestClient, tiingoRestClient);
    }

    // ============================================================
    // 1) Tiger SDK
    // ============================================================
    @Test
    @DisplayName("Tiger SDK: hasCredentials=false → 跳过 Tiger，尝试下一源")
    void tigerNoCredentials_skipsToNextSource() throws Exception {
        when(tigerOpenPythonBridge.hasCredentials()).thenReturn(false);
        when(yahooFinanceRestClient.fetchDailyChart(anyString(), anyString()))
                .thenReturn(createEmptyKLineData(TEST_SYMBOL));
        when(twelveDataRestClient.fetchDailyBars(anyString(), anyInt()))
                .thenReturn(createEmptyKLineData(TEST_SYMBOL));
        when(tiingoRestClient.fetchDailyBars(anyString(), anyInt()))
                .thenReturn(createEmptyKLineData(TEST_SYMBOL));

        Optional<KLineData> result = marketDataSourceRouter.fetchDailyBars(TEST_SYMBOL, null, 7);

        assertThat(result).isEmpty();
        verify(tigerOpenPythonBridge, never()).fetchDailyBars(anyString(), anyInt());
        System.out.println("[Tiger SDK] hasCredentials=false → correctly skipped (expected)");
    }

    @Test
    @DisplayName("Tiger SDK: hasCredentials=true → 优先调用 Tiger 并返回数据")
    void tigerHasCredentialsReturnsData() throws Exception {
        KLineData mockData = createMockKLineData(TEST_SYMBOL);
        when(tigerOpenPythonBridge.hasCredentials()).thenReturn(true);
        when(tigerOpenPythonBridge.fetchDailyBars(TEST_SYMBOL, 7)).thenReturn(mockData);

        Optional<KLineData> result = marketDataSourceRouter.fetchDailyBars(TEST_SYMBOL, null, 7);

        assertThat(result).isPresent();
        assertThat(result.get().getSymbol()).isEqualTo(TEST_SYMBOL);
        verify(tigerOpenPythonBridge).fetchDailyBars(TEST_SYMBOL, 7);
        verify(yahooFinanceRestClient, never()).fetchDailyChart(anyString(), anyString());
        System.out.println("[Tiger SDK] credentials valid + data returned → SUCCESS");
    }

    @Test
    @DisplayName("Tiger SDK: 返回空数据 → fallback 到下一源")
    void tigerReturnsEmpty_fallsback() throws Exception {
        when(tigerOpenPythonBridge.hasCredentials()).thenReturn(true);
        when(tigerOpenPythonBridge.fetchDailyBars(TEST_SYMBOL, 7))
                .thenReturn(createEmptyKLineData(TEST_SYMBOL));
        when(yahooFinanceRestClient.fetchDailyChart(anyString(), anyString()))
                .thenReturn(createEmptyKLineData(TEST_SYMBOL));
        when(twelveDataRestClient.fetchDailyBars(anyString(), anyInt()))
                .thenReturn(createEmptyKLineData(TEST_SYMBOL));
        when(tiingoRestClient.fetchDailyBars(anyString(), anyInt()))
                .thenReturn(createEmptyKLineData(TEST_SYMBOL));

        Optional<KLineData> result = marketDataSourceRouter.fetchDailyBars(TEST_SYMBOL, null, 7);

        assertThat(result).isEmpty();
        verify(yahooFinanceRestClient).fetchDailyChart(anyString(), anyString());
        System.out.println("[Tiger SDK] returns empty → fallback to next source (expected)");
    }

    @Test
    @DisplayName("Tiger SDK: 抛出异常 → fallback 到下一源")
    void tigerThrows_fallsback() throws Exception {
        when(tigerOpenPythonBridge.hasCredentials()).thenReturn(true);
        when(tigerOpenPythonBridge.fetchDailyBars(TEST_SYMBOL, 7))
                .thenThrow(new RuntimeException("Tiger SDK auth error"));
        when(yahooFinanceRestClient.fetchDailyChart(anyString(), anyString()))
                .thenReturn(createEmptyKLineData(TEST_SYMBOL));
        when(twelveDataRestClient.fetchDailyBars(anyString(), anyInt()))
                .thenReturn(createEmptyKLineData(TEST_SYMBOL));
        when(tiingoRestClient.fetchDailyBars(anyString(), anyInt()))
                .thenReturn(createEmptyKLineData(TEST_SYMBOL));

        Optional<KLineData> result = marketDataSourceRouter.fetchDailyBars(TEST_SYMBOL, null, 7);

        assertThat(result).isEmpty();
        verify(yahooFinanceRestClient).fetchDailyChart(anyString(), anyString());
        System.out.println("[Tiger SDK] throws auth error → fallback to next source (expected)");
    }

    // ============================================================
    // 2) YFinance
    // ============================================================
    @Test
    @DisplayName("YFinance: 返回空 → fallback 到 TwelveData")
    void yfinanceEmpty_fallsback() throws Exception {
        when(tigerOpenPythonBridge.hasCredentials()).thenReturn(false);
        when(yahooFinanceRestClient.fetchDailyChart(TEST_SYMBOL, BARS_RANGE))
                .thenReturn(createEmptyKLineData(TEST_SYMBOL));
        when(twelveDataRestClient.fetchDailyBars(TEST_SYMBOL, 7))
                .thenReturn(createEmptyKLineData(TEST_SYMBOL));
        when(tiingoRestClient.fetchDailyBars(anyString(), anyInt()))
                .thenReturn(createEmptyKLineData(TEST_SYMBOL));

        Optional<KLineData> result = marketDataSourceRouter.fetchDailyBars(TEST_SYMBOL, null, 7);

        assertThat(result).isEmpty();
        verify(yahooFinanceRestClient).fetchDailyChart(TEST_SYMBOL, BARS_RANGE);
        verify(twelveDataRestClient).fetchDailyBars(TEST_SYMBOL, 7);
        System.out.println("[YFinance] returns empty → fallback to TwelveData (expected)");
    }

    // ============================================================
    // 3) TwelveData
    // ============================================================
    @Test
    @DisplayName("TwelveData: 限流异常 → fallback 到 Tiingo")
    void twelveDataRateLimited_fallsback() throws Exception {
        when(tigerOpenPythonBridge.hasCredentials()).thenReturn(false);
        when(yahooFinanceRestClient.fetchDailyChart(anyString(), anyString()))
                .thenReturn(createEmptyKLineData(TEST_SYMBOL));
        doThrow(new RuntimeException("HTTP 429 Too Many Requests"))
                .when(twelveDataRestClient).fetchDailyBars(TEST_SYMBOL, 7);
        when(tiingoRestClient.fetchDailyBars(anyString(), anyInt()))
                .thenReturn(createEmptyKLineData(TEST_SYMBOL));

        Optional<KLineData> result = marketDataSourceRouter.fetchDailyBars(TEST_SYMBOL, null, 7);

        assertThat(result).isEmpty();
        verify(tiingoRestClient).fetchDailyBars(TEST_SYMBOL, 7);
        System.out.println("[TwelveData] rate limited (429) → fallback to Tiingo (expected)");
    }

    @Test
    @DisplayName("TwelveData: 返回空 items → fallback 到 Tiingo")
    void twelveDataEmptyItems_fallsback() throws Exception {
        when(tigerOpenPythonBridge.hasCredentials()).thenReturn(false);
        when(yahooFinanceRestClient.fetchDailyChart(anyString(), anyString()))
                .thenReturn(createEmptyKLineData(TEST_SYMBOL));
        when(twelveDataRestClient.fetchDailyBars(TEST_SYMBOL, 7))
                .thenReturn(createEmptyKLineData(TEST_SYMBOL));
        when(tiingoRestClient.fetchDailyBars(anyString(), anyInt()))
                .thenReturn(createEmptyKLineData(TEST_SYMBOL));

        Optional<KLineData> result = marketDataSourceRouter.fetchDailyBars(TEST_SYMBOL, null, 7);

        assertThat(result).isEmpty();
        verify(tiingoRestClient).fetchDailyBars(TEST_SYMBOL, 7);
        System.out.println("[TwelveData] empty items → fallback to Tiingo (expected)");
    }

    // ============================================================
    // 4) Tiingo
    // ============================================================
    @Test
    @DisplayName("Tiingo: 返回有效数据")
    void tiingoReturnsData() throws Exception {
        KLineData tiingoData = createMockKLineData(TEST_SYMBOL);
        when(tigerOpenPythonBridge.hasCredentials()).thenReturn(false);
        when(yahooFinanceRestClient.fetchDailyChart(anyString(), anyString()))
                .thenReturn(createEmptyKLineData(TEST_SYMBOL));
        when(twelveDataRestClient.fetchDailyBars(anyString(), anyInt()))
                .thenReturn(createEmptyKLineData(TEST_SYMBOL));
        when(tiingoRestClient.fetchDailyBars(TEST_SYMBOL, 7))
                .thenReturn(tiingoData);

        Optional<KLineData> result = marketDataSourceRouter.fetchDailyBars(TEST_SYMBOL, null, 7);

        assertThat(result).isPresent();
        assertThat(result.get().getSymbol()).isEqualTo(TEST_SYMBOL);
        System.out.println("[Tiingo] returns valid data → SUCCESS");
    }

    @Test
    @DisplayName("Tiingo: 限流 → 最终返回空")
    void tiingoRateLimited() throws Exception {
        when(tigerOpenPythonBridge.hasCredentials()).thenReturn(false);
        when(yahooFinanceRestClient.fetchDailyChart(anyString(), anyString()))
                .thenReturn(createEmptyKLineData(TEST_SYMBOL));
        when(twelveDataRestClient.fetchDailyBars(anyString(), anyInt()))
                .thenReturn(createEmptyKLineData(TEST_SYMBOL));
        doThrow(new RuntimeException("HTTP 429: rate limit exceeded"))
                .when(tiingoRestClient).fetchDailyBars(TEST_SYMBOL, 7);

        Optional<KLineData> result = marketDataSourceRouter.fetchDailyBars(TEST_SYMBOL, null, 7);

        assertThat(result).isEmpty();
        System.out.println("[Tiingo] rate limited (429) → handled gracefully (expected)");
    }

    // ============================================================
    // 5) 全部不可用
    // ============================================================
    @Test
    @DisplayName("全部数据源抛出异常 → 返回空")
    void allSourcesUnavailable_returnsEmpty() throws Exception {
        when(tigerOpenPythonBridge.hasCredentials()).thenReturn(true);
        doThrow(new RuntimeException("Tiger: auth error"))
                .when(tigerOpenPythonBridge).fetchDailyBars(TEST_SYMBOL, 7);
        doThrow(new RuntimeException("YFinance: connection timeout"))
                .when(yahooFinanceRestClient).fetchDailyChart(anyString(), anyString());
        doThrow(new RuntimeException("TwelveData: rate limit"))
                .when(twelveDataRestClient).fetchDailyBars(anyString(), anyInt());
        doThrow(new RuntimeException("Tiingo: quota exceeded"))
                .when(tiingoRestClient).fetchDailyBars(anyString(), anyInt());

        Optional<KLineData> result = marketDataSourceRouter.fetchDailyBars(TEST_SYMBOL, null, 7);

        assertThat(result).isEmpty();
        verify(tigerOpenPythonBridge).fetchDailyBars(TEST_SYMBOL, 7);
        verify(yahooFinanceRestClient).fetchDailyChart(anyString(), anyString());
        verify(twelveDataRestClient).fetchDailyBars(anyString(), anyInt());
        verify(tiingoRestClient).fetchDailyBars(anyString(), anyInt());
        System.out.println("[All Sources] all unavailable → returns empty (expected)");
    }

    // ============================================================
    // 6) 优先级
    // ============================================================
    @Test
    @DisplayName("Tiger 成功 → 不调用后续源")
    void dataSourcePriorityOrder() throws Exception {
        KLineData tigerData = createMockKLineData(TEST_SYMBOL);
        when(tigerOpenPythonBridge.hasCredentials()).thenReturn(true);
        when(tigerOpenPythonBridge.fetchDailyBars(TEST_SYMBOL, 7)).thenReturn(tigerData);

        Optional<KLineData> result = marketDataSourceRouter.fetchDailyBars(TEST_SYMBOL, null, 7);

        assertThat(result).isPresent();
        assertThat(result.get().getSymbol()).isEqualTo(TEST_SYMBOL);
        verify(yahooFinanceRestClient, never()).fetchDailyChart(anyString(), anyString());
        verify(twelveDataRestClient, never()).fetchDailyBars(anyString(), anyInt());
        verify(tiingoRestClient, never()).fetchDailyBars(anyString(), anyInt());
        System.out.println("[DataSource Priority] Tiger → success, lower sources not called (expected)");
    }

    // ============================================================
    // 7) 完整 fallback 路径测试
    // ============================================================
    @Test
    @DisplayName("Tiger 失败 → YFinance 成功")
    void tigerFailsYFinanceSucceeds() throws Exception {
        when(tigerOpenPythonBridge.hasCredentials()).thenReturn(true);
        when(tigerOpenPythonBridge.fetchDailyBars(TEST_SYMBOL, 7))
                .thenReturn(createEmptyKLineData(TEST_SYMBOL));

        KLineData yfData = createMockKLineData(TEST_SYMBOL);
        when(yahooFinanceRestClient.fetchDailyChart(TEST_SYMBOL, BARS_RANGE))
                .thenReturn(yfData);

        Optional<KLineData> result = marketDataSourceRouter.fetchDailyBars(TEST_SYMBOL, null, 7);

        assertThat(result).isPresent();
        assertThat(result.get().getSymbol()).isEqualTo(TEST_SYMBOL);
        verify(twelveDataRestClient, never()).fetchDailyBars(anyString(), anyInt());
        System.out.println("[Full Path] Tiger fails → YFinance succeeds (expected)");
    }

    @Test
    @DisplayName("Tiger+YFinance 失败 → TwelveData 成功")
    void tigerAndYFinanceFail_twelveDataSucceeds() throws Exception {
        when(tigerOpenPythonBridge.hasCredentials()).thenReturn(true);
        when(tigerOpenPythonBridge.fetchDailyBars(TEST_SYMBOL, 7))
                .thenReturn(createEmptyKLineData(TEST_SYMBOL));
        when(yahooFinanceRestClient.fetchDailyChart(anyString(), anyString()))
                .thenReturn(createEmptyKLineData(TEST_SYMBOL));

        KLineData tdData = createMockKLineData(TEST_SYMBOL);
        when(twelveDataRestClient.fetchDailyBars(TEST_SYMBOL, 7))
                .thenReturn(tdData);

        Optional<KLineData> result = marketDataSourceRouter.fetchDailyBars(TEST_SYMBOL, null, 7);

        assertThat(result).isPresent();
        assertThat(result.get().getSymbol()).isEqualTo(TEST_SYMBOL);
        verify(tiingoRestClient, never()).fetchDailyBars(anyString(), anyInt());
        System.out.println("[Full Path] Tiger+YFinance fail → TwelveData succeeds (expected)");
    }
}
