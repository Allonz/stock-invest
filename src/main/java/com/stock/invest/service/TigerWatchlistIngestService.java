package com.stock.invest.service;

import com.stock.invest.dto.TigerWatchlistIngestRequestDto;
import com.stock.invest.dto.TigerWatchlistIngestResponseDto;

public interface TigerWatchlistIngestService {

    String SNAPSHOT_SOURCE = "tiger_snap";

    TigerWatchlistIngestResponseDto ingest(TigerWatchlistIngestRequestDto request);
}
