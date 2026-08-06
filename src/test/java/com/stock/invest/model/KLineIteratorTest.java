package com.stock.invest.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class KLineIteratorTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void setTimeStringWithValidNumericTimestampShouldSetTime() {
        KLineIterator it = new KLineIterator();
        it.setTime("1700000000000");
        assertEquals(1700000000000L, it.getTime());
        assertEquals("1700000000000", it.getTimeString());
    }

    @Test
    void setTimeStringWithInvalidInputShouldSetTimeToZero() {
        KLineIterator it = new KLineIterator();
        it.setTime("invalid");
        assertEquals(0L, it.getTime(), "Invalid time string should result in time=0");
        assertEquals("invalid", it.getTimeString());
    }

    @Test
    void setTimeStringWithNullShouldSetTimeToZero() {
        KLineIterator it = new KLineIterator();
        it.setTime((String) null);
        assertEquals(0L, it.getTime(), "Null time string should result in time=0");
        assertNull(it.getTimeString());
    }

    @Test
    void setTimeStringWithEmptyShouldSetTimeToZero() {
        KLineIterator it = new KLineIterator();
        it.setTime("");
        assertEquals(0L, it.getTime(), "Empty time string should result in time=0");
        assertEquals("", it.getTimeString());
    }

    @Test
    void setTimeWithLongShouldWorkNormally() {
        KLineIterator it = new KLineIterator();
        it.setTime(123456789L);
        assertEquals(123456789L, it.getTime());
        assertNull(it.getTimeString());
    }

    @Test
    void setTimeWithLongAndThenStringShouldOverride() {
        KLineIterator it = new KLineIterator();
        it.setTime(999L);
        assertEquals(999L, it.getTime());

        it.setTime("888");
        assertEquals(888L, it.getTime());
        assertEquals("888", it.getTimeString());
    }

    @Test
    void gettersAndSettersShouldWork() {
        KLineIterator it = new KLineIterator();
        
        it.setSymbol("AAPL");
        assertEquals("AAPL", it.getSymbol());
        
        it.setOpen(bd("150.5"));
        assertEquals(0, bd("150.5").compareTo(it.getOpen()));
        
        it.setHigh(bd("155.0"));
        assertEquals(0, bd("155.0").compareTo(it.getHigh()));
        
        it.setLow(bd("148.5"));
        assertEquals(0, bd("148.5").compareTo(it.getLow()));
        
        it.setClose(bd("152.0"));
        assertEquals(0, bd("152.0").compareTo(it.getClose()));
        
        it.setVolume(1000000L);
        assertEquals(1000000L, it.getVolume());
        
        it.setAmount(5000000.0);
        assertEquals(5000000.0, it.getAmount(), 0.001);
        
        it.setTimeString("2024-01-03");
        assertEquals("2024-01-03", it.getTimeString());
    }

    @Test
    void constructorWithAllFieldsShouldWork() {
        KLineIterator it = new KLineIterator("AAPL", 1700000000000L,
                bd("150.0"), bd("155.0"), bd("148.0"), bd("152.0"),
                1000000L, 5000000.0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        
        assertEquals("AAPL", it.getSymbol());
        assertEquals(1700000000000L, it.getTime());
        assertEquals(0, bd("150.0").compareTo(it.getOpen()));
        assertEquals(0, bd("155.0").compareTo(it.getHigh()));
        assertEquals(0, bd("148.0").compareTo(it.getLow()));
        assertEquals(0, bd("152.0").compareTo(it.getClose()));
        assertEquals(1000000L, it.getVolume());
        assertEquals(5000000.0, it.getAmount(), 0.001);
    }

    @Test
    void toStringShouldContainFields() {
        KLineIterator it = new KLineIterator("AAPL", 123L,
                bd("1.0"), bd("2.0"), bd("0.5"), bd("1.5"), 100L, 200.0);
        String str = it.toString();
        assertTrue(str.contains("AAPL"));
        assertTrue(str.contains("123"));
        assertTrue(str.contains("1.5"));
    }

    @Test
    void setTimeStringShouldSetTimeZeroForNull() {
        KLineIterator it = new KLineIterator();
        it.setTimeString(null);
        assertNull(it.getTimeString());
    }

    @Test
    void defaultValues() {
        KLineIterator it = new KLineIterator();
        assertEquals(0L, it.getTime());
        assertNull(it.getOpen());
        assertNull(it.getHigh());
        assertNull(it.getLow());
        assertNull(it.getClose());
        assertEquals(0L, it.getVolume());
        assertEquals(0.0, it.getAmount(), 0.001);
        assertNull(it.getSymbol());
        assertNull(it.getTimeString());
    }
}
