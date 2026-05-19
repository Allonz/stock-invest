package com.stock.invest.service;

import java.time.LocalDate;
import java.util.List;

import com.stock.invest.enums.dto.ScreenerRunResponseDto;
import com.stock.invest.enums.dto.ScreeningResultDto;

public interface ScanOrchestratorService {

    ScreenerRunResponseDto runDailyScan(LocalDate tradeDate, int limit);

    ScreenerRunResponseDto runDailyScan(LocalDate tradeDate, int limit, int windowDays);

    /**
     * 仅使用数据库中 {@link com.stock.invest.service.TigerWatchlistIngestService#SNAPSHOT_SOURCE} 导入的 K 线做筛选（不请求外部行情 API）。
     */
    ScreenerRunResponseDto runDailyScanFromSnapshotImport(LocalDate tradeDate, int limit);

    ScreenerRunResponseDto runDailyScanFromSnapshotImport(LocalDate tradeDate, int limit, int windowDays);

    List<ScreeningResultDto> queryByDate(LocalDate tradeDate, Double minPrice, Double maxPrice);

    List<ScreeningResultDto> queryLatest(Double minPrice, Double maxPrice);
}
