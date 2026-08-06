package com.stock.invest.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模型字段测试 — KLineIterator（新增 changePercent/afterHours/afterHoursChangePercent）
 * 覆盖 KLI-001 ~ KLI-006
 * 注意：KLineIterator 有 11 参数构造器。
 */
@DisplayName("KLineIterator — 新增字段（changePercent/afterHours/afterHoursChangePercent）")
class KLineIteratorFieldTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    // KLI-001: 11 参数构造器正确设置新增字段
    @Test
    @DisplayName("KLI-001: 11 参数构造器设置全部新增字段")
    void constructorSetsNewFields() {
        KLineIterator it = new KLineIterator(
                "AAPL", 1700000000000L,  // symbol, time
                bd("150.0"), bd("155.0"), bd("148.0"), bd("152.5"),  // open, high, low, close
                1_000_000L, 5_000_000.0,  // volume, amount
                bd("1.67"),    // changePercent
                bd("153.0"),   // afterHours
                bd("0.33")     // afterHoursChangePercent
        );

        assertEquals("AAPL", it.getSymbol());
        assertEquals(1700000000000L, it.getTime());
        assertEquals(0, bd("150.0").compareTo(it.getOpen()));
        assertEquals(0, bd("155.0").compareTo(it.getHigh()));
        assertEquals(0, bd("148.0").compareTo(it.getLow()));
        assertEquals(0, bd("152.5").compareTo(it.getClose()));
        assertEquals(1_000_000L, it.getVolume());
        assertEquals(5_000_000.0, it.getAmount(), 0.001);
        assertEquals(0, bd("1.67").compareTo(it.getChangePercent()));
        assertEquals(0, bd("153.0").compareTo(it.getAfterHours()));
        assertEquals(0, bd("0.33").compareTo(it.getAfterHoursChangePercent()));
    }

    // KLI-002: changePercent getter/setter
    @Test
    @DisplayName("KLI-002: changePercent getter/setter")
    void changePercentGetterSetter() {
        KLineIterator it = new KLineIterator();
        assertNull(it.getChangePercent(), "default is null");

        it.setChangePercent(bd("-2.5"));
        assertEquals(0, bd("-2.5").compareTo(it.getChangePercent()));

        it.setChangePercent(bd("3.33"));
        assertEquals(0, bd("3.33").compareTo(it.getChangePercent()));
    }

    // KLI-003: afterHours getter/setter
    @Test
    @DisplayName("KLI-003: afterHours getter/setter")
    void afterHoursGetterSetter() {
        KLineIterator it = new KLineIterator();
        assertNull(it.getAfterHours(), "default is null");

        it.setAfterHours(bd("155.5"));
        assertEquals(0, bd("155.5").compareTo(it.getAfterHours()));

        it.setAfterHours(BigDecimal.ZERO);
        assertEquals(0, BigDecimal.ZERO.compareTo(it.getAfterHours()));
    }

    // KLI-004: afterHoursChangePercent getter/setter
    @Test
    @DisplayName("KLI-004: afterHoursChangePercent getter/setter")
    void afterHoursChangePercentGetterSetter() {
        KLineIterator it = new KLineIterator();
        assertNull(it.getAfterHoursChangePercent(), "default is null");

        it.setAfterHoursChangePercent(bd("0.88"));
        assertEquals(0, bd("0.88").compareTo(it.getAfterHoursChangePercent()));

        it.setAfterHoursChangePercent(bd("-1.2"));
        assertEquals(0, bd("-1.2").compareTo(it.getAfterHoursChangePercent()));
    }

    // KLI-005: toString 包含新增字段
    @Test
    @DisplayName("KLI-005: toString 包含 changePercent/afterHours/afterHoursChangePercent")
    void toStringContainsNewFields() {
        KLineIterator it = new KLineIterator(
                "MSFT", 1700000000000L,
                bd("300.0"), bd("305.0"), bd("298.0"), bd("302.5"),
                2_000_000L, 10_000_000.0,
                bd("0.83"), bd("303.0"), bd("0.17")
        );
        String str = it.toString();
        assertTrue(str.contains("changePercent=0.83"), "toString should contain changePercent");
        assertTrue(str.contains("afterHours=303.0"), "toString should contain afterHours");
        assertTrue(str.contains("afterHoursChangePercent=0.17"), "toString should contain afterHoursChangePercent");
    }

    // KLI-006: 默认值（无参构造器）
    @Test
    @DisplayName("KLI-006: 无参构造器默认值")
    void defaultValues() {
        KLineIterator it = new KLineIterator();
        assertNull(it.getChangePercent(), "default changePercent is null");
        assertNull(it.getAfterHours(), "default afterHours is null");
        assertNull(it.getAfterHoursChangePercent(), "default afterHoursChangePercent is null");
    }
}
