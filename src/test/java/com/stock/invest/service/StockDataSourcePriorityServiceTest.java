package com.stock.invest.service;

import com.stock.invest.entity.StockDataSourcePriority;
import com.stock.invest.repository.StockDataSourcePriorityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StockDataSourcePriorityService Tests")
class StockDataSourcePriorityServiceTest {

    @Mock
    private StockDataSourcePriorityRepository repository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private StockDataSourcePriorityService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        service = new StockDataSourcePriorityService(repository, transactionManager);
    }

    @Test
    @DisplayName("getPriorityList: no history returns default order")
    void test_getPriorityList_noHistory() {
        when(repository.findBySymbolOrderByLastSuccessTimeDesc("AAPL")).thenReturn(List.of());

        List<String> result = service.getPriorityList("AAPL");

        assertEquals(StockDataSourcePriorityService.DEFAULT_DATA_SOURCE_ORDER, result);
    }

    @Test
    @DisplayName("getPriorityList: with history returns sorted + default appended")
    void test_getPriorityList_withHistory() {
        StockDataSourcePriority rec1 = StockDataSourcePriority.of("AAPL", "twelvedata", LocalDateTime.now());
        StockDataSourcePriority rec2 = StockDataSourcePriority.of("AAPL", "yfinance", LocalDateTime.now().minusDays(1));

        when(repository.findBySymbolOrderByLastSuccessTimeDesc("AAPL")).thenReturn(List.of(rec1, rec2));

        List<String> result = service.getPriorityList("AAPL");

        // twelvedata first (most recent), then yfinance, then remaining defaults
        assertEquals("twelvedata", result.get(0));
        assertEquals("yfinance", result.get(1));
        // Remaining defaults should be appended（tiger 已删除 2026-08-14）
        assertTrue(result.contains("tiingo"));
        assertTrue(result.contains("tigeropen"));
        assertEquals(4, result.size());
    }

    @Test
    @DisplayName("getPriorityList: null symbol returns default order")
    void test_getPriorityList_nullSymbol() {
        List<String> result = service.getPriorityList(null);

        assertEquals(StockDataSourcePriorityService.DEFAULT_DATA_SOURCE_ORDER, result);
    }

    @Test
    @DisplayName("updatePriority: existing record -> update lastSuccessTime (P2-3)")
    void test_updatePriority_existing() {
        LocalDateTime now = LocalDateTime.now();
        StockDataSourcePriority existing = StockDataSourcePriority.of("AAPL", "twelvedata", now.minusDays(1));
        existing.setId(1L);
        when(repository.findBySymbolAndDataSource("AAPL", "twelvedata"))
                .thenReturn(java.util.Optional.of(existing));

        service.updatePriority("AAPL", "twelvedata", now);

        verify(repository).save(existing);
        assertEquals(now, existing.getLastSuccessTime());
        verify(repository, never()).deleteBySymbolAndDataSource(anyString(), anyString());
    }

    @Test
    @DisplayName("updatePriority: no existing record -> insert new (P2-3)")
    void test_updatePriority_insert() {
        LocalDateTime now = LocalDateTime.now();
        when(repository.findBySymbolAndDataSource("AAPL", "twelvedata"))
                .thenReturn(java.util.Optional.empty());

        service.updatePriority("AAPL", "twelvedata", now);

        ArgumentCaptor<StockDataSourcePriority> captor = ArgumentCaptor.forClass(StockDataSourcePriority.class);
        verify(repository).save(captor.capture());
        assertEquals("AAPL", captor.getValue().getSymbol());
        assertEquals("twelvedata", captor.getValue().getDataSource());
        assertEquals(now, captor.getValue().getLastSuccessTime());
        verify(repository, never()).deleteBySymbolAndDataSource(anyString(), anyString());
    }

    @Test
    @DisplayName("getPriorityRecords: delegates to repository")
    void test_getPriorityRecords() {
        StockDataSourcePriority rec = StockDataSourcePriority.of("AAPL", "yfinance", LocalDateTime.now());
        when(repository.findBySymbolOrderByLastSuccessTimeDesc("AAPL")).thenReturn(List.of(rec));

        var result = service.getPriorityRecords("AAPL");

        assertEquals(1, result.size());
        assertEquals("yfinance", result.get(0).getDataSource());
    }

    @Test
    @DisplayName("getAllRecords: delegates to repository with pagination")
    void test_getAllRecords() {
        StockDataSourcePriority rec = StockDataSourcePriority.of("AAPL", "yfinance", LocalDateTime.now());
        Page<StockDataSourcePriority> page = new PageImpl<>(List.of(rec));
        when(repository.findAll(any(Pageable.class))).thenReturn(page);

        var result = service.getAllRecords(org.springframework.data.domain.PageRequest.of(0, 100));

        assertEquals(1, result.getTotalElements());
        verify(repository).findAll(any(Pageable.class));
    }
}
