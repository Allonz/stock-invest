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
}
