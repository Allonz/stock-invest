package com.stock.invest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.stock.invest.service.StockService;

@SpringBootApplication
public class StockInvestApplication {
    private static final Logger logger = LoggerFactory.getLogger(StockInvestApplication.class);

    public static void main(String[] args) {
        // 检查命令行参数，看看用户指定了哪个数据源
        String dataSource = getDataSourceFromArgs(args);
        if (dataSource != null) {
            logger.info("命令行参数指定数据源: {}", dataSource);
            System.setProperty("spring.profiles.active", dataSource);
        }
        
        // 启动应用
        ConfigurableApplicationContext context = SpringApplication.run(StockInvestApplication.class, args);
        
        // 打印当前激活的profiles
        String[] activeProfiles = context.getEnvironment().getActiveProfiles();
        logger.info("当前激活的profiles: {}", String.join(", ", activeProfiles));
        
        // 如果没有激活的profile，则使用默认profile
        if (activeProfiles.length == 0) {
            String[] defaultProfiles = context.getEnvironment().getDefaultProfiles();
            logger.info("没有激活的profile，使用默认profile: {}", String.join(", ", defaultProfiles));
        }
    }
    
    /**
     * 从命令行参数中获取数据源名称
     * 支持格式: --datasource=tiger 或 -ds=tiger
     * @param args 命令行参数
     * @return 数据源名称，如果没有指定则返回null
     */
    private static String getDataSourceFromArgs(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--datasource=")) {
                return arg.substring("--datasource=".length());
            } else if (arg.startsWith("-ds=")) {
                return arg.substring("-ds=".length());
            }
        }
        return null;
    }
    
    @Component
    public static class StockServiceStartupLogger {
        private static final Logger logger = LoggerFactory.getLogger(StockServiceStartupLogger.class);
        
        @Autowired
        private StockService stockService;
        
        @EventListener
        public void onApplicationStarted(ApplicationStartedEvent event) {
            logger.info("==== 当前使用的StockService实现是: {} ====", stockService.getClass().getName());
        }
    }
} 