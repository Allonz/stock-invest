package com.stock.invest.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析老虎客户端常见成交量展示：纯数字、千分位、中文 万 / 亿、全角逗号、科学计数法（P2-19）。
 * <p>原实现缺陷：纯数字直接 (long) 截断小数、仅替换半角逗号、正则不支持科学计数法与全角逗号，
 * 导致成交量失真（如 12.5万、全角'，'、1.2E5）。</p>
 */
public final class WatchlistVolumeParser {

    /** 数字部分支持小数与科学计数法（1.2E5 / 1.5e3） */
    private static final Pattern NUMERIC = Pattern.compile(
            "^\\s*([0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)\\s*(万|亿)?\\s*$");

    private WatchlistVolumeParser() {
    }

    public static long parseVolumeLong(String raw) {
        if (raw == null) {
            return 0L;
        }
        // P2-19：同时剔除半角/全角逗号（\uFF0C）
        String s = raw.trim().replaceAll("[,\uFF0C]", "");
        if (s.isEmpty() || "0".equals(s)) {
            return 0L;
        }
        Matcher m = NUMERIC.matcher(s);
        if (!m.matches()) {
            throw new IllegalArgumentException("无法解析成交量: " + raw);
        }
        // P2-19：BigDecimal 解析（支持科学计数法），不再用 double 截断
        BigDecimal base = new BigDecimal(m.group(1));
        String unit = m.group(2);
        if ("万".equals(unit)) {
            return base.multiply(BigDecimal.valueOf(10_000L))
                    .setScale(0, RoundingMode.HALF_UP).longValue();
        }
        if ("亿".equals(unit)) {
            return base.multiply(BigDecimal.valueOf(100_000_000L))
                    .setScale(0, RoundingMode.HALF_UP).longValue();
        }
        // 无单位：四舍五入而非截断
        return base.setScale(0, RoundingMode.HALF_UP).longValue();
    }
}
