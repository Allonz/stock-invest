package com.stock.invest.enums.dto;

import java.time.LocalDate;

/**
 * Interface projection for ScreeningMatch query results.
 * Avoids loading full entities when only specific fields are needed.
 */
public interface ScreeningMatchProjection {
    String getSymbol();
    Double getPrice();
    Boolean getRise();
    String getDataSource();
    LocalDate getTradeDate();
    String getBatchId();
    Double getLastClose();
}
