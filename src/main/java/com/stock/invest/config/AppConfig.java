package com.stock.invest.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 应用程序全局配置类
 */
@Configuration
public class AppConfig {
    
   // private static final Logger logger = LoggerFactory.getLogger(AppConfig.class);
    
    /**
     * 创建ObjectMapper Bean
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
} 