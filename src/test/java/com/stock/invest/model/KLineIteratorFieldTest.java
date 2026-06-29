package com.stock.invest.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模型字段测试 — KLineIterator（新增 changePercent/afterHours/afterHoursChangePercent）
 * 覆盖 KLI-001 ~ KLI-006
 * 注意：KLineIterator 有 11 参数构造器。
 */
@DisplayName("KLineIterator — 新增字段（changePercent/afterHours/afterHoursChangePercent）")
class KLineIteratorFieldTest {

    // KLI-001: 11 参数构造器正确设置新增字段
    @Test
    @DisplayName("KLI-001: 11 参数构造器设置全部新增字段")
    void constructorSetsNewFields() {
        KLineIterator it = new KLineIterator(
                "AAPL", 1700000000000L,  // symbol, time
                150.0, 155.0, 148.0, 152.5,  // open, high, low, close
                1_000_000L, 5_000_000.0,  // volume, amount
                1.67,    // changePercent
                153.0,   // afterHours
                0.33     // afterHoursChangePercent
        );

        assertEquals("AAPL", it.getSymbol());
        assertEquals(1700000000000L, it.getTime());
        assertEquals(150.0, it.getOpen(), 0.001);
        assertEquals(155.0, it.getHigh(), 0.001);
        assertEquals(148.0, it.getLow(), 0.001);
        assertEquals(152.5, it.getClose(), 0.001);
        assertEquals(1_000_000L, it.getVolume());
        assertEquals(5_000_000.0, it.getAmount(), 0.001);
        assertEquals(1.67, it.getChangePercent(), 0.001);
        assertEquals(153.0, it.getAfterHours(), 0.001);
        assertEquals(0.33, it.getAfterHoursChangePercent(), 0.001);
    }

    // KLI-002: changePercent getter/setter
    @Test
    @DisplayName("KLI-002: changePercent getter/setter")
    void changePercentGetterSetter() {
        KLineIterator it = new KLineIterator();
        assertEquals(0.0, it.getChangePercent(), 0.001, "default is 0.0");

        it.setChangePercent(-2.5);
        assertEquals(-2.5, it.getChangePercent(), 0.001);

        it.setChangePercent(3.33);
        assertEquals(3.33, it.getChangePercent(), 0.001);
    }

    // KLI-003: afterHours getter/setter
    @Test
    @DisplayName("KLI-003: afterHours getter/setter")
    void afterHoursGetterSetter() {
        KLineIterator it = new KLineIterator();
        assertEquals(0.0, it.getAfterHours(), 0.001, "default is 0.0");

        it.setAfterHours(155.5);
        assertEquals(155.5, it.getAfterHours(), 0.001);

        it.setAfterHours(0.0);
        assertEquals(0.0, it.getAfterHours(), 0.001);
    }

    // KLI-004: afterHoursChangePercent getter/setter
    @Test
    @DisplayName("KLI-004: afterHoursChangePercent getter/setter")
    void afterHoursChangePercentGetterSetter() {
        KLineIterator it = new KLineIterator();
        assertEquals(0.0, it.getAfterHoursChangePercent(), 0.001, "default is 0.0");

        it.setAfterHoursChangePercent(0.88);
        assertEquals(0.88, it.getAfterHoursChangePercent(), 0.001);

        it.setAfterHoursChangePercent(-1.2);
        assertEquals(-1.2, it.getAfterHoursChangePercent(), 0.001);
    }

    // KLI-005: toString 包含新增字段
    @Test
    @DisplayName("KLI-005: toString 包含 changePercent/afterHours/afterHoursChangePercent")
    void toStringContainsNewFields() {
        KLineIterator it = new KLineIterator(
                "MSFT", 1700000000000L,
                300.0, 305.0, 298.0, 302.5,
                2_000_000L, 10_000_000.0,
                0.83, 303.0, 0.17
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
        assertEquals(0.0, it.getChangePercent(), 0.001, "default changePercent is 0.0");
        assertEquals(0.0, it.getAfterHours(), 0.001, "default afterHours is 0.0");
        assertEquals(0.0, it.getAfterHoursChangePercent(), 0.001, "default afterHoursChangePercent is 0.0");
    }
}
