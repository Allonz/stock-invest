package com.stock.invest.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.config.TiingoProperties;
import com.stock.invest.util.PythonScriptExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TiingoDataSourceStrategy — 可用性判断")
class TiingoDataSourceStrategyTest {

    @Mock
    private PythonScriptExecutor pythonScriptExecutor;
    @Mock
    private TiingoProperties tiingoProperties;

    @Test
    @DisplayName("Token 为空时不可用")
    void unavailableWhenTokenBlank() {
        when(tiingoProperties.hasToken()).thenReturn(false);
        TiingoDataSourceStrategy strategy = new TiingoDataSourceStrategy(
                pythonScriptExecutor, tiingoProperties, new ObjectMapper());
        assertFalse(strategy.isAvailable());
    }

    @Test
    @DisplayName("Token 有效时可用")
    void availableWhenTokenPresent() {
        when(tiingoProperties.hasToken()).thenReturn(true);
        TiingoDataSourceStrategy strategy = new TiingoDataSourceStrategy(
                pythonScriptExecutor, tiingoProperties, new ObjectMapper());
        assertTrue(strategy.isAvailable());
    }
}
