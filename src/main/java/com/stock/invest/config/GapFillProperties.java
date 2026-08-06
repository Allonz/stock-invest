package com.stock.invest.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 数据补全相关可配置项。
 */
@Data
@Component
@ConfigurationProperties(prefix = "gap-fill")
public class GapFillProperties {

    /**
     * 最低价格阈值，低于此价格的股票将触发数据补全。
     */
    private BigDecimal minPriceThreshold = BigDecimal.valueOf(1.00);
}
