package com.stock.invest.exception;

/**
 * 股票数据获取异常 - 用于统一处理数据源获取失败的情况。
 * <p>
 * 携带 {@link ErrorCategory} 错误分类，区分"确认不存在 / 瞬态失败 / 账户级错误"三种语义，
 * 供补缺 fallback 链决定是否计入黑名单、是否触发熔断（见 docs/optimization-plan.md P1-3 / P1-5）。
 * </p>
 */
public class StockDataException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 错误分类：
     * <ul>
     *   <li>{@link #CONFIRMED_NOT_FOUND} —— 数据源明确确认股票/数据不存在（404、not found 等），计入黑名单计数；</li>
     *   <li>{@link #TRANSIENT_FAILURE} —— 请求层瞬态失败（超时、连接、5xx、限流、解析异常），不计黑名单；</li>
     *   <li>{@link #ACCOUNT_LEVEL} —— 账户级错误（权限/配额），终止 fallback 链并触发源级熔断。</li>
     * </ul>
     */
    public enum ErrorCategory {
        CONFIRMED_NOT_FOUND,
        TRANSIENT_FAILURE,
        ACCOUNT_LEVEL
    }

    private final String symbol;
    private final String dataSource;
    private final ErrorCategory category;

    public StockDataException(String message) {
        this(null, null, message, null, ErrorCategory.TRANSIENT_FAILURE);
    }

    public StockDataException(String symbol, String dataSource, String message) {
        this(symbol, dataSource, message, null, ErrorCategory.TRANSIENT_FAILURE);
    }

    public StockDataException(String symbol, String dataSource, String message, ErrorCategory category) {
        this(symbol, dataSource, message, null, category);
    }

    public StockDataException(String symbol, String dataSource, String message, Throwable cause) {
        this(symbol, dataSource, message, cause, ErrorCategory.TRANSIENT_FAILURE);
    }

    public StockDataException(String symbol, String dataSource, String message, Throwable cause,
                              ErrorCategory category) {
        super(formatMessage(symbol, dataSource, message), cause);
        this.symbol = symbol;
        this.dataSource = dataSource;
        this.category = category != null ? category : ErrorCategory.TRANSIENT_FAILURE;
    }

    public StockDataException(String message, Throwable cause) {
        super(message, cause);
        this.symbol = null;
        this.dataSource = null;
        this.category = ErrorCategory.TRANSIENT_FAILURE;
    }

    private static String formatMessage(String symbol, String dataSource, String message) {
        if (symbol != null && dataSource != null) {
            return String.format("[%s@%s] %s", symbol, dataSource, message);
        }
        return message;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getDataSource() {
        return dataSource;
    }

    public ErrorCategory getCategory() {
        return category;
    }

    /**
     * 根据错误消息关键词对异常进行分类（P1-5 依赖冻结约束：Tiger SDK 2.2.6 只能从错误码/消息侧识别）。
     * <p>账户级关键词：4000 / permission / quota / 配额 / 权限；其次按 not-found 白名单；其余归为瞬态失败。</p>
     */
    public static StockDataException classify(String symbol, String dataSource, String message, Throwable cause) {
        String lower = message == null ? "" : message.toLowerCase();
        if (lower.contains("4000") || lower.contains("permission") || lower.contains("quota")
                || lower.contains("配额") || lower.contains("权限")) {
            return new StockDataException(symbol, dataSource, "账户级错误: " + message, cause, ErrorCategory.ACCOUNT_LEVEL);
        }
        if (isNotFoundMessage(lower)) {
            return new StockDataException(symbol, dataSource, "股票不存在: " + message, cause, ErrorCategory.CONFIRMED_NOT_FOUND);
        }
        return new StockDataException(symbol, dataSource, "请求失败: " + message, cause, ErrorCategory.TRANSIENT_FAILURE);
    }

    /**
     * not-found 白名单关键词匹配（原 DataGapFillerServiceImpl.isNotFoundError 路径 B 的白名单）。
     */
    public static boolean isNotFoundMessage(String lower) {
        return lower.contains("404")
                || lower.contains("not found")
                || lower.contains("no data")
                || lower.contains("no historical data")
                || lower.contains("no results")
                || lower.contains("invalid symbol")
                || lower.contains("is missing or invalid")
                || lower.contains("grow or venture")
                || lower.contains("not_found");
    }

    /**
     * 数据源不可用异常
     */
    public static class DataSourceUnavailableException extends StockDataException {
        private static final long serialVersionUID = 1L;
        public DataSourceUnavailableException(String dataSource, String reason) {
            super(null, dataSource, "数据源不可用: " + reason);
        }

        public DataSourceUnavailableException(String dataSource, Throwable cause) {
            super(null, dataSource, "数据源调用失败", cause);
        }
    }

    /**
     * 数据解析异常
     */
    public static class DataParseException extends StockDataException {
        private static final long serialVersionUID = 1L;
        public DataParseException(String symbol, String dataSource, String message) {
            super(symbol, dataSource, "数据解析失败: " + message);
        }

        public DataParseException(String symbol, String dataSource, Throwable cause) {
            super(symbol, dataSource, "数据解析失败", cause);
        }
    }

    /**
     * 符号不存在异常
     */
    public static class SymbolNotFoundException extends StockDataException {
        private static final long serialVersionUID = 1L;
        public SymbolNotFoundException(String symbol, String dataSource) {
            super(symbol, dataSource, "股票代码不存在或无数据");
        }
    }
}
