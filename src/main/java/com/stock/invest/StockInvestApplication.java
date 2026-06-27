package com.stock.invest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.stock.invest")
@EnableScheduling
@EnableCaching
public class StockInvestApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockInvestApplication.class, args);
    }
}
