package com.stock.invest.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("scanExecutor")
    public Executor scanAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("scan-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        // P1-8：队列满时拒绝提交（AbortPolicy），避免数小时任务内联进 HTTP 线程拖垮 Tomcat worker
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 日历查询专用小线程池（评审 D-1）：与 scanExecutor 隔离，避免"编排/筛选/补缺
     * 长任务占满 scanExecutor 线程并在内部同步等待日历结果（future.get 60s）"造成的
     * 结构性自饥饿 —— 日历查询任务排队进 scanExecutor 而 worker 全被等待方占死时，
     * 全部查询 60s 超时返回 null，补缺静默跳过当天。
     * <p>日历查询本身已有 15s/30s 级下层超时（HTTP/Python 执行器），
     * core 2/max 4 的小池足以并发支撑少量源并行查询。</p>
     */
    @Bean("calendarExecutor")
    public Executor calendarExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("calendar-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
