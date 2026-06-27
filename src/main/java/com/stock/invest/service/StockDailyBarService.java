package com.stock.invest.service;

import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.enums.dto.StockDailyBarCandleDto;
import com.stock.invest.repository.StockDailyBarRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class StockDailyBarService {

    @Autowired
    private StockDailyBarRepository repository;

    public List<StockDailyBarCandleDto> getRecentCandles(String symbol, int days) {
        List<StockDailyBar> bars = repository.findTop7BySymbolOrderByTradeDateDesc(symbol);
        Collections.reverse(bars);
        if (bars.size() > days) {
            bars = bars.subList(bars.size() - days, bars.size());
        }
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
}
