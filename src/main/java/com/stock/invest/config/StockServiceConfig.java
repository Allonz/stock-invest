package com.stock.invest.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.service.StockService;
import com.stock.invest.service.impl.TigerStockServiceImpl;
import com.stock.invest.service.impl.TwelveDataStockServiceImpl;
import com.stock.invest.service.impl.YFinanceStockServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * 股票服务配置类
 * 根据不同的profile选择不同的StockService实现
 */
@Configuration
public class StockServiceConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(StockServiceConfig.class);
    
    @Autowired
    private ObjectMapper objectMapper;
    
    /**
     * Tiger实现
     * 当激活的是tiger profile时使用此实现
     */
    @Bean
    @Primary
    @Profile("tiger")
    public StockService tigerStockService() {
        logger.info("创建Tiger股票服务实例");
        return new TigerStockServiceImpl(objectMapper);
    }
    
    /**
     * YFinance实现
     * 当激活的是yfinance profile时使用此实现
     */
    @Bean
    @Primary
    @Profile("yfinance")
    public StockService yFinanceStockService() {
        logger.info("创建YFinance股票服务实例");
        return new YFinanceStockServiceImpl(objectMapper);
    }
    
    /**
     * TwelveData实现
     * 当激活的是twelvedata profile时使用此实现
     */
    @Bean
    @Primary
    @Profile("twelvedata")
    public StockService twelveDataStockService() {
        logger.info("创建TwelveData股票服务实例");
        return new TwelveDataStockServiceImpl(objectMapper);
    }
} 