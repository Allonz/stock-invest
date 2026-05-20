package com.stock.invest.constant;

import java.util.List;

/**
 * 统一窗口天数常量定义。
 * <p>
 * 与模式评估（volume pattern evaluation）相关的窗口天数统一在此管理，
 * 避免各服务类硬编码分散重复。</p>
 */
public final class WindowConstants {

    private WindowConstants() {}

    /** 最小窗口天数 */
    public static final int MIN_WINDOW_DAYS = 2;

    /** 最大窗口天数 */
    public static final int MAX_WINDOW_DAYS = 7;

    /** 全部可用窗口天数集合 */
    public static final List<Integer> ALL_WINDOW_DAYS = List.of(2, 3, 4, 5, 6, 7);
}
