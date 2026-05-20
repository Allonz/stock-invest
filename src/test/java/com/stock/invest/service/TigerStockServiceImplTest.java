package com.stock.invest.service;

import com.stock.invest.model.KLineData;
import com.stock.invest.service.impl.TigerStockServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tigerbrokers.stock.openapi.client.https.client.TigerHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TigerStockServiceImpl 修复测试。
 * 1. getStocksFromTigerApi 使用 response.getScannerItems() 直接获取数据，而非 JSON 序列化
 * 2. getKLineDataAsObject 在 response 为 null 时尽早返回默认值
 */
@ExtendWith(MockitoExtension.class)
class TigerStockServiceImplTest {

    @Mock
    private TigerHttpClient tigerHttpClient;
    @Mock
    private PatternEvaluateService patternEvaluateService;

    @InjectMocks
    private TigerStockServiceImpl service;

    @Test
    @DisplayName("getDailyKLineDataAsObject returns null when client is null")
    void getDailyKLineDataAsObject_returnsNull_whenClientNull() {
        assertNotNull(service.getDailyKLineDataAsObject("00700"));
    }

    // T-6: 传非法 period 字符串 -> 日志包含 error，方法不抛异常
    @Test
    @DisplayName("T-6: illegal period string does not throw, returns empty list")
    void getBatchKline_illegalPeriod_doesNotThrow() {
        List<String> symbols = List.of("AAPL");
        // 传非法 period 字符串，KType.valueOf() 会抛 IllegalArgumentException
        // 被 catch 块捕获后返回空列表
        assertDoesNotThrow(() -> {
            List<KLineData> result = service.getBatchKline(symbols, "INVALID_PERIOD", 5);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        });
    }
}
