package com.stock.invest.service.impl;

import com.stock.invest.entity.DataFillTask;
import com.stock.invest.repository.DataFillTaskRepository;
import com.stock.invest.service.RetryProgressService;
import com.stock.invest.service.SymbolBlacklistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 重试任务处理器。
 *
 * <p>负责处理历史补缺失败任务：过期停止、每日重试上限、冷却跳过、
 * 黑名单停止，以及失败后计数递增和成功完成状态流转。</p>
 */
class RetryTaskProcessor {

    private static final Logger log = LoggerFactory.getLogger(RetryTaskProcessor.class);

    private static final ZoneId AMERICA_NY = ZoneId.of("America/New_York");
    private static final int DAILY_RETRY_LIMIT = 5;
    private static final long RETRY_COOLDOWN_MINUTES = 30;

    private final DataFillTaskRepository dataFillTaskRepository;
    private final SymbolBlacklistService symbolBlacklistService;
    private final RetryProgressService retryProgressService;
    private final GapFetcher gapFetcher;
    private final TransactionTemplate transactionTemplate;
    private final AtomicInteger optimisticLockConflicts = new AtomicInteger(0);

    RetryTaskProcessor(DataFillTaskRepository dataFillTaskRepository,
                       SymbolBlacklistService symbolBlacklistService,
                       RetryProgressService retryProgressService,
                       GapFetcher gapFetcher,
                       PlatformTransactionManager transactionManager) {
        this.dataFillTaskRepository = dataFillTaskRepository;
        this.symbolBlacklistService = symbolBlacklistService;
        this.retryProgressService = retryProgressService;
        this.gapFetcher = gapFetcher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    void processRetryingTasksInternal() {
        log.info("");
        log.info("[RetryTaskProcessor] processRetryingTasks: === BEGIN ===");

        RetryProgressService.RetryProgress progress = retryProgressService.startRetry();
        progress.setStage("SCANNING");

        try {
            List<DataFillTask> retryable = dataFillTaskRepository.findRetryableTasks();
            progress.setTotal(retryable.size());
            progress.setStage("RETRYING");
            log.info("[RetryTaskProcessor] processRetryingTasks: found retryingTasks={}", retryable.size());

            LocalDate today = ZonedDateTime.now(AMERICA_NY).toLocalDate();
            int retried = 0;
            for (DataFillTask task : retryable) {
                String symbol = task.getSymbol();
                LocalDate tradeDate = task.getTradeDate();

                Instant weekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
                if (!task.getCreatedAt().isAfter(weekAgo)) {
                    task.setStatus("stopped");
                    saveTaskWithOptimisticLock(task);
                    progress.incrementProcessed();
                    log.info("[RetryTaskProcessor] processRetryingTasks: task expired taskId={}, symbol={}, date={}",
                            task.getId(), symbol, tradeDate);
                    continue;
                }

                if (today.equals(task.getRetryDate()) && task.getDayCount() != null
                        && task.getDayCount() >= DAILY_RETRY_LIMIT) {
                    progress.incrementProcessed();
                    log.info("[RetryTaskProcessor] processRetryingTasks: daily limit reached taskId={}, symbol={}, date={}, dayCount={}",
                            task.getId(), symbol, tradeDate, task.getDayCount());
                    continue;
                }

                if (!today.equals(task.getRetryDate())) {
                    task.setDayCount(0);
                    task.setRetryDate(today);
                }

                if (task.getUpdatedAt() != null) {
                    Instant cooldownEnd = task.getUpdatedAt().plus(RETRY_COOLDOWN_MINUTES, ChronoUnit.MINUTES);
                    if (cooldownEnd.isAfter(Instant.now())) {
                        progress.incrementProcessed();
                        log.info("[RetryTaskProcessor] processRetryingTasks: cooldown taskId={}, symbol={}, date={}, updatedAt={}",
                                task.getId(), symbol, tradeDate, task.getUpdatedAt());
                        continue;
                    }
                }

                if (symbolBlacklistService.isBlacklisted(symbol)) {
                    task.setStatus("stopped");
                    task.setLastError("symbol is blacklisted");
                    saveTaskWithOptimisticLock(task);
                    progress.incrementProcessed();
                    log.info("[RetryTaskProcessor] processRetryingTasks: task stopped (blacklisted) taskId={}, symbol={}, date={}",
                            task.getId(), symbol, tradeDate);
                    continue;
                }

                String retryMsg = String.format("processRetryingTasks: retrying taskId=%d, symbol=%s, date=%s, attempt=%d/%d",
                        task.getId(), symbol, tradeDate, task.getRetryCount() + 1, task.getMaxRetries());
                String retrySep = "=".repeat(retryMsg.length());
                log.info("");
                log.info("[RetryTaskProcessor] {}", retrySep);
                log.info("[RetryTaskProcessor] {}", retryMsg);
                log.info("[RetryTaskProcessor] {}", retrySep);

                boolean success = gapFetcher.fetchAndPersist(symbol, tradeDate).succeeded();
                progress.incrementProcessed();
                if (success) {
                    task.setStatus("completed");
                    saveTaskWithOptimisticLock(task);
                    progress.incrementSucceeded();
                    log.info("[RetryTaskProcessor] processRetryingTasks: retry success taskId={}, symbol={}, date={}",
                            task.getId(), symbol, tradeDate);
                    retried++;
                } else {
                    if (symbolBlacklistService.isBlacklisted(symbol)) {
                        task.setStatus("stopped");
                        task.setLastError("blacklisted after all sources exhausted");
                        saveTaskWithOptimisticLock(task);
                        progress.incrementFailed();
                        log.info("[RetryTaskProcessor] processRetryingTasks: task stopped (newly blacklisted) taskId={}, symbol={}, date={}",
                                task.getId(), symbol, tradeDate);
                    } else {
                        final long taskId = task.getId();
                        runInTx(() -> {
                            dataFillTaskRepository.resetDailyCounterIfDateChanged(taskId, today);
                            dataFillTaskRepository.incrementRetryCounters(taskId, "retrying", "retry attempt failed again");
                        });
                        task.setRetryCount(task.getRetryCount() + 1);
                        task.setDayCount(task.getDayCount() + 1);
                        task.setStatus("retrying");
                        task.setLastError("retry attempt failed again");
                        progress.incrementFailed();
                        log.warn("[RetryTaskProcessor] processRetryingTasks: retry failed taskId={}, symbol={}, date={}, retryCount={}, dayCount={}",
                                task.getId(), symbol, tradeDate, task.getRetryCount(), task.getDayCount());
                    }
                }
            }

            log.info("[RetryTaskProcessor] processRetryingTasks: === COMPLETED === retried={}, total={}",
                    retried, retryable.size());
        } finally {
            progress.setRunning(false);
            progress.setStage("COMPLETED");
        }
    }

    void createRetryTask(String symbol, LocalDate tradeDate, String error) {
        LocalDate today = ZonedDateTime.now(AMERICA_NY).toLocalDate();
        Optional<DataFillTask> existing = dataFillTaskRepository.findBySymbolAndTradeDate(symbol, tradeDate);
        if (existing.isPresent()) {
            DataFillTask task = existing.get();
            runInTx(() -> dataFillTaskRepository.incrementRetryCounters(task.getId(), "retrying", error));
            task.setRetryCount(task.getRetryCount() + 1);
            task.setStatus("retrying");
            task.setLastError(error);
            log.info("[RetryTaskProcessor] createRetryTask: updated symbol={}, date={}, retryCount={}, error={}",
                    symbol, tradeDate, task.getRetryCount(), error);
            return;
        }
        DataFillTask task = new DataFillTask();
        task.setSymbol(symbol);
        task.setTradeDate(tradeDate);
        task.setStatus("retrying");
        task.setRetryCount(1);
        task.setRetryDate(today);
        task.setDayCount(1);
        task.setLastError(error);
        saveTaskWithOptimisticLock(task);
        log.info("[RetryTaskProcessor] createRetryTask: created symbol={}, date={}, error={}",
                symbol, tradeDate, error);
    }

    private void saveTaskWithOptimisticLock(DataFillTask task) {
        try {
            runInTx(() -> dataFillTaskRepository.save(task));
        } catch (ObjectOptimisticLockingFailureException e) {
            DataFillTask latest = dataFillTaskRepository.findById(task.getId()).orElse(null);
            if (latest == null) {
                log.error("[RetryTaskProcessor] optimistic lock conflict on taskId={} but row not found, update dropped: {}",
                        task.getId(), e.getMessage());
                return;
            }
            log.warn("[RetryTaskProcessor] optimistic lock conflict on taskId={}, re-read and replay once: {}",
                    task.getId(), e.getMessage());
            // 终态字段以本次意图为准直接覆盖（status/lastError/retryDate）
            latest.setStatus(task.getStatus());
            latest.setLastError(task.getLastError());
            latest.setRetryDate(task.getRetryDate());
            // 日计数重置意图（retryDate 变更 → dayCount 置 0）一并重放
            if (!Objects.equals(task.getRetryDate(), latest.getRetryDate())) {
                latest.setDayCount(task.getDayCount());
            }
            try {
                runInTx(() -> dataFillTaskRepository.save(latest));
            } catch (ObjectOptimisticLockingFailureException e2) {
                optimisticLockConflicts.incrementAndGet();
                log.error("[RetryTaskProcessor] optimistic lock conflict on taskId={} after replay, update dropped (conflictTotal={}): {}",
                        task.getId(), optimisticLockConflicts.get(), e2.getMessage());
            }
        }
    }

    private void runInTx(Runnable action) {
        transactionTemplate.executeWithoutResult(status -> action.run());
    }
}
