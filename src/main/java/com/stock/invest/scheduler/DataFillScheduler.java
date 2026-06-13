package com.stock.invest.scheduler;

import com.stock.invest.service.DataGapFillerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 数据补全定时调度：每天 19:00 美东时间执行一次。
 */
@Component
public class DataFillScheduler {

    private static final Logger log = LoggerFactory.getLogger(DataFillScheduler.class);

    private final DataGapFillerService dataGapFillerService;

    public DataFillScheduler(DataGapFillerService dataGapFillerService) {
        this.dataGapFillerService = dataGapFillerService;
    }

    @Scheduled(cron = "0 0 19 * * ?", zone = "America/New_York")
    public void runDataFill() {
        Instant schedulerStart = Instant.now();
        log.info("[DataFillScheduler] fillGaps: === SCHEDULER START === at {}", LocalDateTime.now());

        try {
            dataGapFillerService.fillGaps();
        } catch (Exception e) {
            log.error("[DataFillScheduler] fillGaps: failed — error={}", e.getMessage(), e);
        }

        Instant retryStart = Instant.now();
        log.info("[DataFillScheduler] processRetryingTasks: begin");
        try {
            dataGapFillerService.processRetryingTasks();
        } catch (Exception e) {
            log.error("[DataFillScheduler] processRetryingTasks: failed — error={}", e.getMessage(), e);
        }
        log.info("[DataFillScheduler] processRetryingTasks: completed — elapsedMs={}",
                Duration.between(retryStart, Instant.now()).toMillis());

        log.info("[DataFillScheduler] fillGaps: === SCHEDULER END === elapsedMs={}",
                Duration.between(schedulerStart, Instant.now()).toMillis());
    }
}
