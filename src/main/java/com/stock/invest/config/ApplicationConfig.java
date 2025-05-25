package com.stock.invest.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 应用程序配置类
 * 确保所有组件能被Spring正确扫描
 */
@Configuration
@ComponentScan(basePackages = {
    "com.stock.invest",
    "com.stock.invest.controller",
    "com.stock.invest.service",
    "com.stock.invest.service.impl",
    "com.stock.invest.model",
    "com.stock.invest.demo"
})
public class ApplicationConfig {
    // 可以添加其他配置方法
} 