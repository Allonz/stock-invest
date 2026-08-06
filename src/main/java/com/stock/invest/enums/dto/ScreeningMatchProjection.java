package com.stock.invest.enums.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Interface projection for ScreeningMatch query results.
 * Avoids loading full entities when only specific fields are needed.
 */
public interface ScreeningMatchProjection {
    String getSymbol();
    BigDecimal getPrice();
    Boolean getRise();
    String getDataSource();
    LocalDate getTradeDate();
    String getBatchId();
    BigDecimal getLastClose();
}
