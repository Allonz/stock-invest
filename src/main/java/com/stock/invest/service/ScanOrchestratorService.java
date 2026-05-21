package com.stock.invest.service;

import java.time.LocalDate;
import java.util.List;

import com.stock.invest.enums.dto.ScreeningResultDto;

public interface ScanOrchestratorService {

    List<ScreeningResultDto> queryByDate(LocalDate tradeDate, Double minPrice, Double maxPrice);

    List<ScreeningResultDto> queryLatest(Double minPrice, Double maxPrice);
}
