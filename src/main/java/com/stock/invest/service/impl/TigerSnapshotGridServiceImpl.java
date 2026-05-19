package com.stock.invest.service.impl;

import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.enums.dto.SnapshotGridRowDto;
import com.stock.invest.enums.dto.SnapshotGridViewDto;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.TigerSnapshotGridService;
import com.stock.invest.service.TigerWatchlistIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

@Service
public class TigerSnapshotGridServiceImpl implements TigerSnapshotGridService {

    private static final Logger log = LoggerFactory.getLogger(TigerSnapshotGridServiceImpl.class);
    private static final int DEFAULT_MAX_DATES = 48;

    private final StockDailyBarRepository stockDailyBarRepository;

    public TigerSnapshotGridServiceImpl(StockDailyBarRepository stockDailyBarRepository) {
        this.stockDailyBarRepository = stockDailyBarRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public SnapshotGridViewDto buildGrid(int maxDateColumns) {
        int cap = maxDateColumns > 0 ? maxDateColumns : DEFAULT_MAX_DATES;
        String src = TigerWatchlistIngestService.SNAPSHOT_SOURCE;
        List<LocalDate> allDates = stockDailyBarRepository.findDistinctTradeDatesBySourceAsc(src);
        if (allDates.isEmpty()) {
            return new SnapshotGridViewDto(Collections.emptyList(), Collections.emptyList());
        }
        int from = Math.max(0, allDates.size() - cap);
        List<LocalDate> dates = new ArrayList<>(allDates.subList(from, allDates.size()));
        List<StockDailyBar> bars = stockDailyBarRepository.findBySourceAndTradeDateInOrderBySymbolAscTradeDateAsc(src, dates);

        Map<String, TreeMap<LocalDate, StockDailyBar>> bySymbol = new LinkedHashMap<>();
        for (StockDailyBar b : bars) {
            bySymbol.computeIfAbsent(b.getSymbol(), k -> new TreeMap<>()).put(b.getTradeDate(), b);
        }

        TreeSet<String> orderedSymbols = new TreeSet<>(bySymbol.keySet());
        List<String> headers = new ArrayList<>();
        for (LocalDate d : dates) {
            headers.add(d.toString());
        }

        List<SnapshotGridRowDto> rows = new ArrayList<>();
        for (String sym : orderedSymbols) {
            TreeMap<LocalDate, StockDailyBar> dayMap = bySymbol.get(sym);
            String name = resolveName(dayMap);
            List<String> cells = new ArrayList<>();
            for (LocalDate d : dates) {
                StockDailyBar bar = dayMap != null ? dayMap.get(d) : null;
                cells.add(bar == null ? "—" : formatCell(bar));
            }
            rows.add(new SnapshotGridRowDto(sym, name, cells));
        }
        return new SnapshotGridViewDto(headers, rows);
    }

    private static String resolveName(TreeMap<LocalDate, StockDailyBar> dayMap) {
        if (dayMap == null || dayMap.isEmpty()) {
            return "";
        }
        StockDailyBar latest = dayMap.lastEntry().getValue();
        if (latest.getName() != null && !latest.getName().trim().isEmpty()) {
            return latest.getName().trim();
        }
        for (StockDailyBar b : dayMap.values()) {
            if (b.getName() != null && !b.getName().trim().isEmpty()) {
                return b.getName().trim();
            }
        }
        return "";
    }

    private static String formatCell(StockDailyBar b) {
        double px = b.getClosePrice() == null ? 0D : b.getClosePrice();
        long v = b.getVolume() == null ? 0L : b.getVolume();
        return String.format(Locale.US, "%.4f / %,d", px, v);
    }
}
