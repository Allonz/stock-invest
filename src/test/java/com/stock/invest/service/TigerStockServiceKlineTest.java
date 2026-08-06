package com.stock.invest.service;

import com.stock.invest.model.KLineIterator;
import com.stock.invest.service.impl.TigerStockServiceImpl;
import com.tigerbrokers.stock.openapi.client.https.domain.quote.item.KlinePoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * KLINE-PT-001~003: TigerStockServiceImpl.convertTigerKlinePointToKLineIterator 单元测试。
 * 使用反射调用 private static 方法，Mock KlinePoint 验证字段映射。
 */
@ExtendWith(MockitoExtension.class)
class TigerStockServiceKlineTest {

    @Mock
    private KlinePoint mockKlinePoint;

    private Method convertMethod;

    @BeforeEach
    void setUp() throws Exception {
        convertMethod = TigerStockServiceImpl.class.getDeclaredMethod(
                "convertTigerKlinePointToKLineIterator", String.class, KlinePoint.class);
        convertMethod.setAccessible(true);
    }

    // ---- KLINE-PT-001: 正常 KlinePoint 映射为 KLineIterator ----

    @Test
    @DisplayName("KLINE-PT-001: convertTigerKlinePointToKLineIterator maps all fields correctly")
    void convertTigerKlinePointToKLineIterator_mapsAllFields() throws Exception {
        when(mockKlinePoint.getTime()).thenReturn(1719331200000L);
        when(mockKlinePoint.getOpen()).thenReturn(150.0);
        when(mockKlinePoint.getHigh()).thenReturn(152.0);
        when(mockKlinePoint.getLow()).thenReturn(149.0);
        when(mockKlinePoint.getClose()).thenReturn(151.0);
        when(mockKlinePoint.getVolume()).thenReturn(1000000L);
        when(mockKlinePoint.getAmount()).thenReturn(151000000.0);

        KLineIterator result = (KLineIterator) convertMethod.invoke(null, "AAPL", mockKlinePoint);

        assertNotNull(result);
        assertEquals("AAPL", result.getSymbol());
        assertEquals(1719331200000L, result.getTime());
        assertEquals(0, java.math.BigDecimal.valueOf(150.0).compareTo(result.getOpen()));
        assertEquals(0, java.math.BigDecimal.valueOf(152.0).compareTo(result.getHigh()));
        assertEquals(0, java.math.BigDecimal.valueOf(149.0).compareTo(result.getLow()));
        assertEquals(0, java.math.BigDecimal.valueOf(151.0).compareTo(result.getClose()));
        assertEquals(1000000L, result.getVolume());
        assertEquals(151000000.0, result.getAmount(), 0.001);
        // Default values
        assertEquals(0, java.math.BigDecimal.ZERO.compareTo(result.getChangePercent()));
        assertEquals(0, java.math.BigDecimal.ZERO.compareTo(result.getAfterHours()));
        assertEquals(0, java.math.BigDecimal.ZERO.compareTo(result.getAfterHoursChangePercent()));
    }

    // ---- KLINE-PT-002: 零值字段处理 ----

    @Test
    @DisplayName("KLINE-PT-002: convertTigerKlinePointToKLineIterator handles zero values")
    void convertTigerKlinePointToKLineIterator_zeroValues() throws Exception {
        when(mockKlinePoint.getTime()).thenReturn(0L);
        when(mockKlinePoint.getOpen()).thenReturn(0.0);
        when(mockKlinePoint.getHigh()).thenReturn(0.0);
        when(mockKlinePoint.getLow()).thenReturn(0.0);
        when(mockKlinePoint.getClose()).thenReturn(0.0);
        when(mockKlinePoint.getVolume()).thenReturn(0L);
        when(mockKlinePoint.getAmount()).thenReturn(0.0);

        KLineIterator result = (KLineIterator) convertMethod.invoke(null, "ZERO", mockKlinePoint);

        assertNotNull(result);
        assertEquals("ZERO", result.getSymbol());
        assertEquals(0L, result.getTime());
        assertEquals(0, java.math.BigDecimal.ZERO.compareTo(result.getOpen()));
        assertEquals(0, java.math.BigDecimal.ZERO.compareTo(result.getClose()));
        assertEquals(0L, result.getVolume());
        assertEquals(0.0, result.getAmount(), 0.001);
    }

    // ---- KLINE-PT-003: 大数值边界情况 ----

    @Test
    @DisplayName("KLINE-PT-003: convertTigerKlinePointToKLineIterator handles large values")
    void convertTigerKlinePointToKLineIterator_largeValues() throws Exception {
        when(mockKlinePoint.getTime()).thenReturn(9999999999999L);
        when(mockKlinePoint.getOpen()).thenReturn(99999.99);
        when(mockKlinePoint.getHigh()).thenReturn(100000.0);
        when(mockKlinePoint.getLow()).thenReturn(0.01);
        when(mockKlinePoint.getClose()).thenReturn(50000.50);
        when(mockKlinePoint.getVolume()).thenReturn(999999999L);
        when(mockKlinePoint.getAmount()).thenReturn(5.0e10);

        KLineIterator result = (KLineIterator) convertMethod.invoke(null, "BRK.A", mockKlinePoint);

        assertNotNull(result);
        assertEquals("BRK.A", result.getSymbol());
        assertEquals(9999999999999L, result.getTime());
        assertEquals(0, java.math.BigDecimal.valueOf(99999.99).compareTo(result.getOpen()));
        assertEquals(0, java.math.BigDecimal.valueOf(100000.0).compareTo(result.getHigh()));
        assertEquals(0, java.math.BigDecimal.valueOf(0.01).compareTo(result.getLow()));
        assertEquals(0, java.math.BigDecimal.valueOf(50000.50).compareTo(result.getClose()));
        assertEquals(999999999L, result.getVolume());
        assertEquals(5.0e10, result.getAmount(), 0.001);
    }
}
