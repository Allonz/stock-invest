package com.stock.invest.service.impl;

import com.stock.invest.enums.dto.TigerWatchlistIngestRequestDto;
import com.stock.invest.enums.dto.TigerWatchlistRowDto;
import com.stock.invest.repository.StockDailyBarRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayName("TigerWatchlistIngestServiceImpl — 请求校验")
class TigerWatchlistIngestServiceImplTest {

    @Mock
    private StockDailyBarRepository stockDailyBarRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private TigerWatchlistIngestServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        service = new TigerWatchlistIngestServiceImpl(stockDailyBarRepository, transactionManager);
    }

    @Test
    @DisplayName("非法 tradeDate 抛 IllegalArgumentException 而非 DateTimeParseException")
    void invalidTradeDate_throwsIllegalArgumentException() {
        TigerWatchlistIngestRequestDto request = new TigerWatchlistIngestRequestDto(
                "2026-13-40",
                List.of(new TigerWatchlistRowDto("AAPL", "Apple", new BigDecimal("10.0"),
                        new BigDecimal("9.5"), new BigDecimal("10.5"), new BigDecimal("9.0"),
                        BigDecimal.ZERO, null, null, "100")))
        ;
        assertThrows(IllegalArgumentException.class, () -> service.ingest(request),
                "tradeDate 格式非法时必须转成 IllegalArgumentException 以便返回 400");
    }

    @Test
    @DisplayName("空白 tradeDate 抛 IllegalArgumentException")
    void blankTradeDate_throwsIllegalArgumentException() {
        TigerWatchlistIngestRequestDto request = new TigerWatchlistIngestRequestDto(
                "   ",
                List.of(new TigerWatchlistRowDto("AAPL", "Apple", new BigDecimal("10.0"),
                        new BigDecimal("9.5"), new BigDecimal("10.5"), new BigDecimal("9.0"),
                        BigDecimal.ZERO, null, null, "100")))
        ;
        assertThrows(IllegalArgumentException.class, () -> service.ingest(request));
    }
}
