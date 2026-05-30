package com.stock.invest.service.impl;

import com.stock.invest.config.ScannerProperties;
import com.stock.invest.constant.WindowConstants;
import com.stock.invest.entity.ScreeningMatch;
import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.enums.dto.ScreeningMatchProjection;
import com.stock.invest.enums.dto.ScreeningResultDto;
import com.stock.invest.repository.ScreeningMatchRepository;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.MarketDataSourceRouter;
import com.stock.invest.service.PatternEvaluateService;
import com.stock.invest.service.PriceVolumeCacheService;
import com.stock.invest.service.ScanOrchestratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

@Service
public class ScanOrchestratorServiceImpl implements ScanOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(ScanOrchestratorServiceImpl.class);
    private static final int MIN_WINDOW_DAYS = 3;

    private final ScannerProperties scannerProperties;
    private final PatternEvaluateService patternEvaluateService;
    private final ScreeningMatchRepository screeningMatchRepository;
    private final StockDailyBarRepository stockDailyBarRepository;

    /**
     * 统一使用构造函数注入 - 符合 Spring 最佳实践
     */
    public ScanOrchestratorServiceImpl(
            ScannerProperties scannerProperties,
            PatternEvaluateService patternEvaluateService,
            ScreeningMatchRepository screeningMatchRepository,
            StockDailyBarRepository stockDailyBarRepository) {
        this.scannerProperties = scannerProperties;
        this.patternEvaluateService = patternEvaluateService;
        this.screeningMatchRepository = screeningMatchRepository;
        this.stockDailyBarRepository = stockDailyBarRepository;
    }

    @Override
    public List<ScreeningResultDto> queryByDate(LocalDate tradeDate, Double minPrice, Double maxPrice) {
        LocalDate date = tradeDate == null ? ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalDate() : tradeDate;
        double min = minPrice == null ? scannerProperties.getMinPrice() : minPrice;
        double max = maxPrice == null ? scannerProperties.getMaxPrice() : maxPrice;
        List<ScreeningMatchProjection> rows = screeningMatchRepository.findProjectedByTradeDateAndPriceBetweenOrderByPriceDesc(date, min, max);
        return toDto(rows);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScreeningResultDto> queryLatest(Double minPrice, Double maxPrice) {
        Optional<ScreeningMatch> latest = screeningMatchRepository.findTopByOrderByTradeDateDescIdDesc();
        if (!latest.isPresent() || latest.get().getTradeDate() == null) {
            return new ArrayList<>();
        }
        return queryByDate(latest.get().getTradeDate(), minPrice, maxPrice);
    }

    private static List<ScreeningResultDto> toDto(List<? extends ScreeningMatchProjection> rows) {
        List<ScreeningResultDto> out = new ArrayList<>();
        for (ScreeningMatchProjection row : rows) {
            out.add(new ScreeningResultDto(
                    row.getSymbol(),
                    row.getPrice(),
                    row.getRise(),
                    row.getDataSource(),
                    row.getTradeDate()
            ));
        }
        return out;
    }

    private static int sanitizeWindowDays(int windowDays) {
        if (windowDays < MIN_WINDOW_DAYS) {
            return MIN_WINDOW_DAYS;
        }
        if (windowDays > WindowConstants.MAX_WINDOW_DAYS) {
            return WindowConstants.MAX_WINDOW_DAYS;
        }
        return windowDays;
    }
}
