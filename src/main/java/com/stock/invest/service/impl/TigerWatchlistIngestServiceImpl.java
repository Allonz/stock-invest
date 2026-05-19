package com.stock.invest.service.impl;

import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.enums.dto.TigerWatchlistIngestRequestDto;
import com.stock.invest.enums.dto.TigerWatchlistIngestResponseDto;
import com.stock.invest.enums.dto.TigerWatchlistRowDto;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.TigerWatchlistIngestService;
import com.stock.invest.util.WatchlistVolumeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class TigerWatchlistIngestServiceImpl implements TigerWatchlistIngestService {

    private static final Logger log = LoggerFactory.getLogger(TigerWatchlistIngestServiceImpl.class);
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("^[A-Z0-9\\-]{1,32}$");

    private final StockDailyBarRepository stockDailyBarRepository;

    public TigerWatchlistIngestServiceImpl(StockDailyBarRepository stockDailyBarRepository) {
        this.stockDailyBarRepository = stockDailyBarRepository;
    }

    @Override
    @Transactional
    public TigerWatchlistIngestResponseDto ingest(TigerWatchlistIngestRequestDto request) {
        Instant start = Instant.now();

        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (request.tradeDate() == null || request.tradeDate().trim().isEmpty()) {
            throw new IllegalArgumentException("tradeDate is required");
        }
        if (request.rows() == null || request.rows().isEmpty()) {
            throw new IllegalArgumentException("rows must not be empty");
        }
        LocalDate tradeDate = LocalDate.parse(request.tradeDate().trim());
        log.info("[TigerIngest] importScreenCapture: begin — tradeDate={}, totalRows={}", tradeDate, request.rows().size());
        String batchId = UUID.randomUUID().toString();
        int imported = 0;
        int skipped = 0;
        List<String> reasons = new ArrayList<>();

        for (TigerWatchlistRowDto row : request.rows()) {
            if (row == null) {
                skipped++;
                reasons.add("null row");
                continue;
            }
            String sym = resolveSymbol(row);
            if (sym == null || !SYMBOL_PATTERN.matcher(sym).matches()) {
                skipped++;
                reasons.add("invalid symbol: " + sym);
                continue;
            }
            if (row.lastPrice() == null || row.lastPrice() <= 0D) {
                skipped++;
                reasons.add(sym + ": lastPrice invalid");
                continue;
            }
            long vol;
            try {
                vol = WatchlistVolumeParser.parseVolumeLong(volumeToParseString(row.volume()));
            } catch (IllegalArgumentException e) {
                skipped++;
                reasons.add(sym + ": " + e.getMessage());
                continue;
            }
            double px = row.lastPrice();
            StockDailyBar bar = stockDailyBarRepository.findBySymbolAndTradeDate(sym, tradeDate).orElseGet(StockDailyBar::new);
            bar.setSymbol(sym);
            bar.setTradeDate(tradeDate);
            bar.setOpenPrice(px);
            bar.setClosePrice(px);
            bar.setVolume(vol);
            bar.setSource(SNAPSHOT_SOURCE);
            if (row.name() != null && !row.name().trim().isEmpty()) {
                String n = row.name().trim();
                if (n.length() > 128) {
                    n = n.substring(0, 128);
                }
                bar.setName(n);
            }
            stockDailyBarRepository.save(bar);
            log.info("[TigerIngest] importScreenCapture: save symbol={}, price={}, volume={}, date={}",
                    sym, px, vol, tradeDate);
            imported++;
        }

        TigerWatchlistIngestResponseDto out = new TigerWatchlistIngestResponseDto(batchId, tradeDate, imported, skipped, reasons);
        log.info("[TigerIngest] importScreenCapture: completed — batchId={}, tradeDate={}, total={}, imported={}, skipped={}, elapsedMs={}",
                batchId, tradeDate, request.rows().size(), imported, skipped, Duration.between(start, Instant.now()).toMillis());
        if (skipped > 0 && log.isDebugEnabled()) {
            log.debug("[TigerIngest] importScreenCapture: skip reasons: {}", reasons);
        }
        return out;
    }

    private static String volumeToParseString(Object v) {
        if (v == null) {
            return "0";
        }
        if (v instanceof Number) {
            return String.valueOf(((Number) v).longValue());
        }
        return String.valueOf(v);
    }

    private static String resolveSymbol(TigerWatchlistRowDto row) {
        if (row.symbol() != null && !row.symbol().trim().isEmpty()) {
            return row.symbol().trim().toUpperCase();
        }
        return null;
    }
}
