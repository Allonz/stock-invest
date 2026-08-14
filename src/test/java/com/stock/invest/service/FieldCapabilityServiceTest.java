package com.stock.invest.service;

import com.stock.invest.entity.FieldCapability;
import com.stock.invest.repository.FieldCapabilityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * FieldCapabilityService：能力表加载 + 查询逻辑测试（2026-08-14）
 */
@ExtendWith(MockitoExtension.class)
class FieldCapabilityServiceTest {

    @Mock private FieldCapabilityRepository repository;

    private FieldCapabilityService service;

    private static FieldCapability cap(String source, String field, boolean supported, boolean markable, String method) {
        FieldCapability c = new FieldCapability();
        c.setDataSource(source);
        c.setFieldName(field);
        c.setSupported(supported);
        c.setMarkable(markable);
        c.setQueryMethod(method);
        return c;
    }

    @BeforeEach
    void setUp() {
        when(repository.findAll()).thenReturn(List.of(
                cap("yfinance", "after_hours", true, true, FieldCapability.METHOD_AFTER_HOURS_API),
                cap("tiingo", "after_hours", false, false, FieldCapability.METHOD_AFTER_HOURS_API),
                cap("yfinance", "change_percent", true, true, FieldCapability.METHOD_CALC),
                cap("tiingo", "change_percent", true, true, FieldCapability.METHOD_CALC),
                cap("yfinance", "close_price", true, true, FieldCapability.METHOD_DAILY_KLINE)
        ));
        service = new FieldCapabilityService(repository);
        service.load();
    }

    @Test
    @DisplayName("FC-01: 加载后 isMarkable 按能力表返回")
    void isMarkable() {
        assertTrue(service.isMarkable("yfinance", "after_hours"));
        assertFalse(service.isMarkable("tiingo", "after_hours"), "tiingo 不支持盘后 → 不标记");
        assertTrue(service.isMarkable("tiingo", "change_percent"));
        assertFalse(service.isMarkable("unknown", "after_hours"), "未知源 → 不标记");
        assertFalse(service.isMarkable("yfinance", "unknown_field"), "未知字段 → 不标记");
        assertFalse(service.isMarkable(null, "after_hours"));
    }

    @Test
    @DisplayName("FC-02: isSupported 按能力表返回")
    void isSupported() {
        assertTrue(service.isSupported("yfinance", "after_hours"));
        assertFalse(service.isSupported("tiingo", "after_hours"));
        assertTrue(service.isSupported("yfinance", "close_price"));
    }

    @Test
    @DisplayName("FC-03: queryMethod 按字段聚合返回，未知字段默认 DAILY_KLINE")
    void queryMethod() {
        assertEquals(FieldCapability.METHOD_AFTER_HOURS_API, service.queryMethod("after_hours"));
        assertEquals(FieldCapability.METHOD_CALC, service.queryMethod("change_percent"));
        assertEquals(FieldCapability.METHOD_DAILY_KLINE, service.queryMethod("close_price"));
        assertEquals(FieldCapability.METHOD_DAILY_KLINE, service.queryMethod("unknown"));
    }
}
