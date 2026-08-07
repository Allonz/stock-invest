package com.stock.invest.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure unit tests for {@link WatchlistVolumeParser}.
 * Calls the real static method directly — no mocking.
 */
public class WatchlistVolumeParserTest {

    // ── Normal numeric inputs ──────────────────────────────────────────

    @Test
    public void parsePlainNumber() {
        assertEquals(1_000_000L, WatchlistVolumeParser.parseVolumeLong("1000000"));
    }

    @Test
    public void parseCommaFormatted() {
        assertEquals(1_000_000L, WatchlistVolumeParser.parseVolumeLong("1,000,000"));
    }

    @Test
    public void parseLargeNumberWithCommas() {
        assertEquals(12_345_678L, WatchlistVolumeParser.parseVolumeLong("12,345,678"));
    }

    @Test
    public void parseNumberWithLeadingTrailingSpaces() {
        assertEquals(500L, WatchlistVolumeParser.parseVolumeLong("  500  "));
    }

    @Test
    public void parseZero() {
        assertEquals(0L, WatchlistVolumeParser.parseVolumeLong("0"));
    }

    // ── Chinese unit: 万 (ten thousand) ─────────────────────────────────

    @Test
    public void parseChineseWanSimple() {
        assertEquals(5_000_000L, WatchlistVolumeParser.parseVolumeLong("500万"));
    }

    @Test
    public void parseChineseWanWithDecimal() {
        assertEquals(15_000L, WatchlistVolumeParser.parseVolumeLong("1.5万"));
    }

    @Test
    public void parseChineseWanZeroPointFive() {
        assertEquals(5_000L, WatchlistVolumeParser.parseVolumeLong("0.5万"));
    }

    // ── Chinese unit: 亿 (hundred million) ──────────────────────────────

    @Test
    public void parseChineseYiSimple() {
        assertEquals(150_000_000L, WatchlistVolumeParser.parseVolumeLong("1.5亿"));
    }

    @Test
    public void parseChineseYiWhole() {
        assertEquals(200_000_000L, WatchlistVolumeParser.parseVolumeLong("2亿"));
    }

    // ── Null / empty / blank inputs ────────────────────────────────────

    @Test
    public void parseNullReturnsZero() {
        assertEquals(0L, WatchlistVolumeParser.parseVolumeLong(null));
    }

    @Test
    public void parseEmptyStringReturnsZero() {
        assertEquals(0L, WatchlistVolumeParser.parseVolumeLong(""));
    }

    @Test
    public void parseBlankStringReturnsZero() {
        assertEquals(0L, WatchlistVolumeParser.parseVolumeLong("   "));
    }

    // ── Malformed input ────────────────────────────────────────────────

    @Test
    public void parseGarbageThrowsException() {
        assertThrows(IllegalArgumentException.class,
                () -> WatchlistVolumeParser.parseVolumeLong("abc"));
    }

    @Test
    public void parseMixedChineseGarbageThrowsException() {
        assertThrows(IllegalArgumentException.class,
                () -> WatchlistVolumeParser.parseVolumeLong("100abc万"));
    }

    // ── P2-19: 全角逗号 / 小数四舍五入 / 科学计数法 ─────────────────────

    @Test
    public void parseFullWidthComma() {
        assertEquals(12_345_678L, WatchlistVolumeParser.parseVolumeLong("12，345，678"));
    }

    @Test
    public void parseDecimalRoundsInsteadOfTruncates() {
        assertEquals(13L, WatchlistVolumeParser.parseVolumeLong("12.6"));
    }

    @Test
    public void parseScientificNotation() {
        assertEquals(120_000L, WatchlistVolumeParser.parseVolumeLong("1.2E5"));
    }

    @Test
    public void parseScientificNotationWithUnit() {
        assertEquals(15_000_000L, WatchlistVolumeParser.parseVolumeLong("1.5E3万"));
    }

    // ── R2 P3-11: long 溢出保护 ───────────────────────────────────

    @Test
    public void parseOverflowPlainNumberThrows() {
        // 19 位数字远超 Long.MAX_VALUE（9223372036854775807）
        assertThrows(IllegalArgumentException.class,
                () -> WatchlistVolumeParser.parseVolumeLong("99999999999999999999"));
    }

    @Test
    public void parseOverflowYiUnitThrows() {
        // 100亿亿 = 1e18 亿 → 1e26，远超 long 范围
        assertThrows(IllegalArgumentException.class,
                () -> WatchlistVolumeParser.parseVolumeLong("100亿亿"));
    }

    @Test
    public void parseOverflowScientificNotationThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> WatchlistVolumeParser.parseVolumeLong("1E25"));
    }

    @Test
    public void parseLongMaxValueBoundaryOk() {
        // Long.MAX_VALUE 边界值本身可解析（compareTo 严格大于才抛）
        assertEquals(Long.MAX_VALUE, WatchlistVolumeParser.parseVolumeLong("9223372036854775807"));
    }
}
