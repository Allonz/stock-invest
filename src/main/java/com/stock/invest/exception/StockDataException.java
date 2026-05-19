package com.stock.invest.exception;

/**
 * 股票数据获取异常 - 用于统一处理数据源获取失败的情况
 */
public class StockDataException extends RuntimeException {
    
    private static final long serialVersionUID = 1L;
    
    private final String symbol;
    private final String dataSource;
    
    public StockDataException(String message) {
        super(message);
        this.symbol = null;
        this.dataSource = null;
    }
    
    public StockDataException(String symbol, String dataSource, String message) {
        super(String.format("[%s@%s] %s", symbol, dataSource, message));
        this.symbol = symbol;
        this.dataSource = dataSource;
    }
    
    public StockDataException(String symbol, String dataSource, String message, Throwable cause) {
        super(String.format("[%s@%s] %s", symbol, dataSource, message), cause);
        this.symbol = symbol;
        this.dataSource = dataSource;
    }
    
    public StockDataException(String message, Throwable cause) {
        super(message, cause);
        this.symbol = null;
        this.dataSource = null;
    }
    
    public String getSymbol() {
        return symbol;
    }
    
    public String getDataSource() {
        return dataSource;
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