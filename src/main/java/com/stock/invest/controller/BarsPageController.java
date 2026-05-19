package com.stock.invest.controller;

import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.repository.StockDailyBarRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.List;

@Controller
public class BarsPageController {

    private static final Logger log = LoggerFactory.getLogger(BarsPageController.class);

    private final StockDailyBarRepository stockDailyBarRepository;

    public BarsPageController(StockDailyBarRepository stockDailyBarRepository) {
        this.stockDailyBarRepository = stockDailyBarRepository;
    }

    @GetMapping("/page/bars")
    public String bars(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            Model model
    ) {
        List<StockDailyBar> rows;
        LocalDate start = startDate != null ? LocalDate.parse(startDate) : null;
        LocalDate end = endDate != null ? LocalDate.parse(endDate) : null;

        if (symbol != null && !symbol.isBlank()) {
            rows = stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(
                    symbol, org.springframework.data.domain.PageRequest.of(0, 1000));
        } else if (source != null && !source.isBlank()) {
            if (start != null && end != null) {
                rows = stockDailyBarRepository.findBySourceAndTradeDateBetween(source, start, end);
            } else {
                rows = stockDailyBarRepository.findBySourceOrderByTradeDateDesc(source);
            }
        } else if (start != null && end != null) {
            rows = stockDailyBarRepository.findByTradeDateBetweenOrderByTradeDateDesc(start, end);
        } else {
            rows = stockDailyBarRepository.findByTradeDateBetweenOrderByTradeDateDesc(
                    LocalDate.now().minusDays(30), LocalDate.now());
        }

        List<String> allSymbols = stockDailyBarRepository.findAllSymbols();
        List<String> allSources = stockDailyBarRepository.findAllSources();

        model.addAttribute("rows", rows);
        model.addAttribute("allSymbols", allSymbols);
        model.addAttribute("allSources", allSources);
        model.addAttribute("selectedSymbol", symbol);
        model.addAttribute("selectedSource", source);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);

        // Stats
        model.addAttribute("totalSymbols", stockDailyBarRepository.countDistinctSymbols());
        model.addAttribute("totalRecords", stockDailyBarRepository.count());

        return "bars";
    }
}
