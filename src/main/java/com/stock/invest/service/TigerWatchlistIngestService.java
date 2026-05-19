package com.stock.invest.service;

import com.stock.invest.enums.dto.TigerWatchlistIngestRequestDto;
import com.stock.invest.enums.dto.TigerWatchlistIngestResponseDto;

public interface TigerWatchlistIngestService {

    String SNAPSHOT_SOURCE = "tiger_snap";

    TigerWatchlistIngestResponseDto ingest(TigerWatchlistIngestRequestDto request);
}
