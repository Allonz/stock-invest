package com.stock.invest.service;

import com.stock.invest.entity.StockDataSourcePriority;
import com.stock.invest.repository.StockDataSourcePriorityRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

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

    @InjectMocks
    private StockDataSourcePriorityService service;

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
        // Remaining defaults should be appended
        assertTrue(result.contains("tiingo"));
        assertTrue(result.contains("tigeropen"));
        assertTrue(result.contains("tiger"));
        assertEquals(5, result.size());
    }

    @Test
    @DisplayName("getPriorityList: null symbol returns default order")
    void test_getPriorityList_nullSymbol() {
        List<String> result = service.getPriorityList(null);

        assertEquals(StockDataSourcePriorityService.DEFAULT_DATA_SOURCE_ORDER, result);
    }

    @Test
    @DisplayName("updatePriority: deletes old record then saves new")
    void test_updatePriority() {
        LocalDateTime now = LocalDateTime.now();

        service.updatePriority("AAPL", "twelvedata", now);

        verify(repository).deleteBySymbolAndDataSource("AAPL", "twelvedata");
        verify(repository).flush();
        ArgumentCaptor<StockDataSourcePriority> captor = ArgumentCaptor.forClass(StockDataSourcePriority.class);
        verify(repository).save(captor.capture());
        assertEquals("AAPL", captor.getValue().getSymbol());
        assertEquals("twelvedata", captor.getValue().getDataSource());
        assertEquals(now, captor.getValue().getLastSuccessTime());
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
    @DisplayName("getAllRecords: delegates to repository with sort")
    void test_getAllRecords() {
        StockDataSourcePriority rec = StockDataSourcePriority.of("AAPL", "yfinance", LocalDateTime.now());
        when(repository.findAll(any(Sort.class))).thenReturn(List.of(rec));

        var result = service.getAllRecords();

        assertEquals(1, result.size());
        verify(repository).findAll(Sort.by(Sort.Direction.ASC, "symbol"));
    }
}
