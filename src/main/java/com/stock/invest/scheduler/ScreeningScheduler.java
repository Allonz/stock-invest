package com.stock.invest.scheduler;
import com.stock.invest.service.ScreeningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 模式筛选定时调度：每天 21:30 美东时间执行一次。
 */
@Component
public class ScreeningScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScreeningScheduler.class);

    private final ScreeningService screeningService;

    public ScreeningScheduler(ScreeningService screeningService) {
        this.screeningService = screeningService;
    }

    @Scheduled(cron = "0 30 21 * * ?", zone = "America/New_York")
    public void runScreening() {
        Instant start = Instant.now();
        LocalDate today = ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalDate();
        log.info("[ScreeningScheduler] runDailyScreening: === SCHEDULER START === date={}", today);
        try {
            String batchId = screeningService.runScreening(today);
            log.info("[ScreeningScheduler] runDailyScreening: === SCHEDULER END === " +
                    "batchId={}, date={}, elapsedMs={}",
                    batchId, today, Duration.between(start, Instant.now()).toMillis());
        } catch (Exception e) {
            log.error("[ScreeningScheduler] runDailyScreening: === FAILED === " +
                    "date={}, elapsedMs={}, error={}",
                    today, Duration.between(start, Instant.now()).toMillis(), e.getMessage(), e);
        }
    }
}
