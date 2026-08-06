package com.stock.invest.service;

import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.enums.dto.StockDailyBarCandleDto;
import com.stock.invest.enums.dto.StockDailyBarDto;
import com.stock.invest.repository.StockDailyBarRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class StockDailyBarService {

    private final StockDailyBarRepository repository;

    public StockDailyBarService(StockDailyBarRepository repository) {
        this.repository = repository;
    }

    public List<StockDailyBarCandleDto> getRecentCandles(String symbol, int days) {
        // P3-8：原硬编码 findTop7（days 仅 ≤7 时截断），改为页式查询取 min(days,365) 条
        int limit = Math.min(Math.max(1, days), 365);
        List<StockDailyBar> bars = repository.findBySymbolOrderByTradeDateDesc(
                symbol, org.springframework.data.domain.PageRequest.of(0, limit));
        Collections.reverse(bars);
        return bars.stream()
            .map(bar -> {
                return new StockDailyBarCandleDto(
                    bar.getTradeDate().toString(),
                    bar.getOpenPrice(),
                    bar.getHighPrice(),
                    bar.getLowPrice(),
                    bar.getClosePrice(),
                    bar.getChangePercent(),
                    bar.getAfterHours(),
                    bar.getAfterHoursChangePercent(),
                    bar.getVolume()
                );
            })
            .toList();
    }

    /**
     * 按股票代码查询最近的日 K 线数据（DTO）。
     *
     * @param symbol 股票代码
     * @return DTO 列表（最多 500 条）
     */
    public List<StockDailyBarDto> getBarsBySymbol(String symbol) {
        List<StockDailyBar> bars = repository
                .findBySymbolOrderByTradeDateDesc(symbol, org.springframework.data.domain.PageRequest.of(0, 500));
        return bars.stream().map(this::toDto).collect(Collectors.toList());
    }

    /**
     * 分页查询日 K 线数据（DTO），支持按 symbol/tradeDate/source 筛选。
     *
     * @param pageable 分页+排序
     * @param symbol   股票代码（可选）
     * @param date     交易日（可选）
     * @param source   数据源（可选）
     * @return DTO 分页结果
     */
    public Page<StockDailyBarDto> queryBars(Pageable pageable, String symbol, LocalDate date, String source) {
        Page<StockDailyBar> barPage = repository.findFiltered(symbol, date, source, pageable);
        return barPage.map(this::toDto);
    }

    /**
     * 获取所有数据源列表。
     *
     * @return 数据源名称列表
     */
    public List<String> getAllSources() {
        return repository.findAllSources();
    }

    private StockDailyBarDto toDto(StockDailyBar bar) {
        return new StockDailyBarDto(
                bar.getSymbol(),
                bar.getName(),
                bar.getTradeDate(),
                bar.getOpenPrice(),
                bar.getHighPrice(),
                bar.getLowPrice(),
                bar.getClosePrice(),
                bar.getChangePercent(),
                bar.getAfterHours(),
                bar.getAfterHoursChangePercent(),
                bar.getVolume(),
                bar.getSource()
        );
    }
}
