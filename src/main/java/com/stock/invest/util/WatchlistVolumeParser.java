package com.stock.invest.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析老虎客户端常见成交量展示：纯数字、带千分位、中文 万 / 亿。
 */
public final class WatchlistVolumeParser {

    private static final Pattern NUMERIC = Pattern.compile("^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(万|亿)?\\s*$");

    private WatchlistVolumeParser() {
    }

    public static long parseVolumeLong(String raw) {
        if (raw == null) {
            return 0L;
        }
        String s = raw.trim().replace(",", "");
        if (s.isEmpty() || "0".equals(s)) {
            return 0L;
        }
        Matcher m = NUMERIC.matcher(s);
        if (!m.matches()) {
            throw new IllegalArgumentException("无法解析成交量: " + raw);
        }
        double base = Double.parseDouble(m.group(1));
        String unit = m.group(2);
        if (unit == null || unit.isEmpty()) {
            return (long) base;
        }
        if ("万".equals(unit)) {
            return Math.round(base * 10_000L);
        }
        if ("亿".equals(unit)) {
            return Math.round(base * 100_000_000L);
        }
        return (long) base;
    }
}
