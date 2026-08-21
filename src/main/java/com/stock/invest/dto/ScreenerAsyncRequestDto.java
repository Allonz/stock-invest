package com.stock.invest.dto;

/**
 * 高级筛选异步触发入参（评审 A-4）。
 *
 * <p>替代 {@code @RequestBody Map<String,Object>} + 手工 {@code parsePositiveInt} 解析：
 * 两个入参均可缺省（null 时取默认值），compact 构造器统一校验正整数，
 * 非法值抛 {@link IllegalArgumentException} 由全局异常处理器转 400。</p>
 *
 * @param limit      本次筛选的最大候选数，缺省 60，必须为正整数
 * @param windowDays 筛选窗口天数，缺省 7，必须为正整数
 */
public record ScreenerAsyncRequestDto(Integer limit, Integer windowDays) {

    public static final int DEFAULT_LIMIT = 60;
    public static final int DEFAULT_WINDOW_DAYS = 7;

    public ScreenerAsyncRequestDto {
        limit = resolvePositive(limit, "limit", DEFAULT_LIMIT);
        windowDays = resolvePositive(windowDays, "windowDays", DEFAULT_WINDOW_DAYS);
    }

    private static int resolvePositive(Integer value, String key, int defaultValue) {
        int parsed = value == null ? defaultValue : value;
        if (parsed <= 0) {
            throw new IllegalArgumentException(key + " must be a positive integer");
        }
        return parsed;
    }
}
