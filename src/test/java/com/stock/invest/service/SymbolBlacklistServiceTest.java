package com.stock.invest.service;

import com.stock.invest.entity.SymbolBlacklist;
import com.stock.invest.repository.SymbolBlacklistRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SymbolBlacklistService Tests")
class SymbolBlacklistServiceTest {

    @Mock
    private SymbolBlacklistRepository repository;

    @InjectMocks
    private SymbolBlacklistService service;

    @Test
    @DisplayName("recordNotFound: first time creates new record with count=1")
    void test_recordNotFound_firstTime() {
        when(repository.findBySymbol("AAPL")).thenReturn(Optional.empty());

        service.recordNotFound("AAPL", Map.of("yfinance", "not_found"));

        ArgumentCaptor<SymbolBlacklist> captor = ArgumentCaptor.forClass(SymbolBlacklist.class);
        verify(repository).save(captor.capture());
        SymbolBlacklist saved = captor.getValue();
        assertEquals("AAPL", saved.getSymbol());
        assertEquals(1, saved.getConsecutive404Count());
        assertEquals("active", saved.getStatus());
        assertNotNull(saved.getSourceErrors());
        assertTrue(saved.getSourceErrors().contains("yfinance"));
    }

    @Test
    @DisplayName("recordNotFound: existing record increments count")
    void test_recordNotFound_existingRecord() {
        SymbolBlacklist existing = new SymbolBlacklist();
        existing.setSymbol("AAPL");
        existing.setConsecutive404Count(2);
        existing.setStatus("active");

        when(repository.findBySymbol("AAPL")).thenReturn(Optional.of(existing));

        service.recordNotFound("AAPL", Map.of("twelvedata", "not_found"));

        ArgumentCaptor<SymbolBlacklist> captor = ArgumentCaptor.forClass(SymbolBlacklist.class);
        verify(repository).save(captor.capture());
        assertEquals(3, captor.getValue().getConsecutive404Count());
        assertEquals("active", captor.getValue().getStatus());
    }

    @Test
    @DisplayName("isBlacklisted: active status returns true")
    void test_isBlacklisted_active() {
        SymbolBlacklist record = new SymbolBlacklist();
        record.setStatus("active");
        when(repository.findBySymbol("AAPL")).thenReturn(Optional.of(record));

        assertTrue(service.isBlacklisted("AAPL"));
    }

    @Test
    @DisplayName("isBlacklisted: cleared status returns false")
    void test_isBlacklisted_cleared() {
        SymbolBlacklist record = new SymbolBlacklist();
        record.setStatus("cleared");
        when(repository.findBySymbol("AAPL")).thenReturn(Optional.of(record));

        assertFalse(service.isBlacklisted("AAPL"));
    }

    @Test
    @DisplayName("isBlacklisted: no record returns false")
    void test_isBlacklisted_noRecord() {
        when(repository.findBySymbol("NEW")).thenReturn(Optional.empty());

        assertFalse(service.isBlacklisted("NEW"));
    }

    @Test
    @DisplayName("resetCount: sets count=0 and status=cleared")
    void test_resetCount() {
        SymbolBlacklist existing = new SymbolBlacklist();
        existing.setSymbol("AAPL");
        existing.setConsecutive404Count(3);
        existing.setStatus("active");

        when(repository.findBySymbol("AAPL")).thenReturn(Optional.of(existing));

        service.resetCount("AAPL");

        ArgumentCaptor<SymbolBlacklist> captor = ArgumentCaptor.forClass(SymbolBlacklist.class);
        verify(repository).save(captor.capture());
        assertEquals(0, captor.getValue().getConsecutive404Count());
        assertEquals("cleared", captor.getValue().getStatus());
    }

    @Test
    @DisplayName("resetCount: no existing record does nothing")
    void test_resetCount_noRecord() {
        when(repository.findBySymbol("NEW")).thenReturn(Optional.empty());

        service.resetCount("NEW");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("clearSymbol: sets status=cleared")
    void test_clearSymbol() {
        SymbolBlacklist existing = new SymbolBlacklist();
        existing.setSymbol("AAPL");
        existing.setStatus("active");

        when(repository.findBySymbol("AAPL")).thenReturn(Optional.of(existing));

        service.clearSymbol("AAPL");

        ArgumentCaptor<SymbolBlacklist> captor = ArgumentCaptor.forClass(SymbolBlacklist.class);
        verify(repository).save(captor.capture());
        assertEquals("cleared", captor.getValue().getStatus());
    }

    @Test
    @DisplayName("getBlacklistedSymbols: delegates to repository")
    void test_getBlacklistedSymbols() {
        when(repository.findBlacklistedSymbolsWithCountGE3()).thenReturn(List.of("AAPL", "TSLA"));

        List<String> result = service.getBlacklistedSymbols();

        assertEquals(2, result.size());
        assertTrue(result.contains("AAPL"));
        assertTrue(result.contains("TSLA"));
    }
}
