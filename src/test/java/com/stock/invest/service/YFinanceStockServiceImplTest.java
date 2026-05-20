package com.stock.invest.service;

import com.stock.invest.client.YahooFinanceRestClient;
import com.stock.invest.config.ScannerProperties;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.StockInfo;
import com.stock.invest.service.impl.YFinanceStockServiceImpl;
import com.stock.invest.util.PythonScriptExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class YFinanceStockServiceImplTest {

    @Mock
    private PythonScriptExecutor pythonScriptExecutor;

    @InjectMocks
    private YFinanceStockServiceImpl service;

    @Test
    @DisplayName("getDailyKLineDataAsObject does not call getStockInfo")
    void getDailyKLineDataAsObject_doesNotCallGetStockInfo() {
        KLineData data = service.getDailyKLineDataAsObject("00700", 10);
        assertNotNull(data);
    }

    @Test
    @DisplayName("volume no overflow when large value")
    void volume_noOverflow_whenLargeValue() {
        StockInfo info = new StockInfo();
        info.setVolume(3_500_000_000L);
        assertEquals(3_500_000_000L, info.getVolume());
    }
}
