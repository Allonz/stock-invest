package com.stock.invest.constant;

/**
 * 补缺 / 字段增补窗口常量统一管理。
 *
 * <p>评审 E-3：此前 {@code MAX_LOOKBACK_DAYS}（7）与 {@code FILL_WINDOW_CALENDAR_DAYS}（45）
 * 分别在 DataGapFillerServiceImpl / GapDateScanner / MissingFieldFiller 中重复定义，
 * 改一处漏一处；现统一收敛到本常量类，各引用方直接引用。</p>
 */
public final class GapFillConstants {

    private GapFillConstants() {
    }

    /** 补缺回看窗口（自然日）：只考察最近 N 个自然日内的数据缺口（周末/节假日宽松覆盖） */
    public static final int MAX_LOOKBACK_DAYS = 7;

    /** 字段增补窗口（日历日）：只补最近 N 个日历日（≈30 交易日）内的记录；
     *  历史太久的不补（yfinance 盘后分钟数据 30 天窗口外不可得）。 */
    public static final int FILL_WINDOW_CALENDAR_DAYS = 45;
}
