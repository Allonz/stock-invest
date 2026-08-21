package com.stock.invest.service.impl;

import com.stock.invest.config.GapFillProperties;
import com.stock.invest.repository.DataFillTaskRepository;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.DataFillProgressService;
import com.stock.invest.service.DataSourceStrategy;
import com.stock.invest.service.FieldCapabilityService;
import com.stock.invest.service.RetryProgressService;
import com.stock.invest.service.StockDataSourcePriorityService;
import com.stock.invest.service.SymbolBlacklistService;
import com.stock.invest.service.TradingCalendarDbService;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

/**
 * 单元测试装配辅助（评审 A-1 配套）。
 *
 * <p>生产路径下四个协作类（FallbackChainBuilder / MissingFieldFiller / GapFetcher /
 * RetryTaskProcessor）已改为 {@code @Component} Spring Bean，由容器构造注入；
 * 本类仅供纯 Mockito 单测在无 Spring 上下文时按旧 11 参签名装配
 * {@link DataGapFillerServiceImpl}，避免测试逐一构造四个包内可见的协作类。</p>
 */
public final class DataGapFillerTestSupport {

    private DataGapFillerTestSupport() {
    }

    public static DataGapFillerServiceImpl buildService(
            StockDailyBarRepository stockDailyBarRepository,
            DataFillTaskRepository dataFillTaskRepository,
            List<DataSourceStrategy> dataSources,
            GapFillProperties gapFillProperties,
            DataFillProgressService dataFillProgressService,
            RetryProgressService retryProgressService,
            TradingCalendarDbService tradingCalendarDbService,
            StockDataSourcePriorityService stockDataSourcePriorityService,
            SymbolBlacklistService symbolBlacklistService,
            PlatformTransactionManager transactionManager,
            FieldCapabilityService fieldCapabilityService) {
        FallbackChainBuilder fallbackChainBuilder =
                new FallbackChainBuilder(dataSources, stockDataSourcePriorityService);
        MissingFieldFiller missingFieldFiller = new MissingFieldFiller(
                stockDailyBarRepository, dataSources, fieldCapabilityService, transactionManager);
        GapFetcher gapFetcher = new GapFetcher(
                stockDailyBarRepository,
                dataFillTaskRepository,
                stockDataSourcePriorityService,
                symbolBlacklistService,
                missingFieldFiller,
                fallbackChainBuilder,
                transactionManager);
        RetryTaskProcessor retryTaskProcessor = new RetryTaskProcessor(
                dataFillTaskRepository,
                symbolBlacklistService,
                retryProgressService,
                gapFetcher,
                transactionManager);
        return new DataGapFillerServiceImpl(
                stockDailyBarRepository,
                dataFillTaskRepository,
                gapFillProperties,
                dataFillProgressService,
                tradingCalendarDbService,
                symbolBlacklistService,
                missingFieldFiller,
                gapFetcher,
                retryTaskProcessor,
                transactionManager);
    }
}
