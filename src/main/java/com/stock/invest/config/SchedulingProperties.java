package com.stock.invest.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 选股扫描调度相关可配置项。
 */
@Data
@Component
@ConfigurationProperties(prefix = "scanner.scheduling")
public class SchedulingProperties {

    private String dailyCron = "0 40 4 * * MON-FRI";
    private String dailyZone = "America/New_York";
    /** snapshot：仅使用导入的截图 K 线；external_api：从原 MarketDataSourceRouter 拉取 */
    private String scheduledScanSource = "snapshot";
}
