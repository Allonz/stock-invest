package com.stock.invest.enums.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record TigerWatchlistIngestResponseDto(
    String batchId,
    LocalDate tradeDate,
    int imported,
    int skipped,
    List<String> skipReasons
) {
    public TigerWatchlistIngestResponseDto {
        if (skipReasons == null) {
            skipReasons = new ArrayList<>();
        }
    }

    public TigerWatchlistIngestResponseDto withBatchId(String batchId) {
        return new TigerWatchlistIngestResponseDto(batchId, tradeDate, imported, skipped, skipReasons);
    }

    public TigerWatchlistIngestResponseDto withTradeDate(LocalDate tradeDate) {
        return new TigerWatchlistIngestResponseDto(batchId, tradeDate, imported, skipped, skipReasons);
    }

    public TigerWatchlistIngestResponseDto withImported(int imported) {
        return new TigerWatchlistIngestResponseDto(batchId, tradeDate, imported, skipped, skipReasons);
    }

    public TigerWatchlistIngestResponseDto withSkipped(int skipped) {
        return new TigerWatchlistIngestResponseDto(batchId, tradeDate, imported, skipped, skipReasons);
    }

    public TigerWatchlistIngestResponseDto withSkipReasons(List<String> skipReasons) {
        return new TigerWatchlistIngestResponseDto(batchId, tradeDate, imported, skipped,
                skipReasons != null ? skipReasons : new ArrayList<>());
    }

    @Override
    public List<String> skipReasons() {
        return skipReasons != null ? Collections.unmodifiableList(skipReasons) : Collections.emptyList();
    }
}
