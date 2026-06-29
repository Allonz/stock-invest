package com.stock.invest.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.config.TigerApiConfig;
import com.stock.invest.model.KLineData;
import com.stock.invest.util.PythonScriptExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BRIDGE-001~002: TigerOpenPythonBridge 盘后价查询单元测试。
 * Mock PythonScriptExecutor，验证 fetchAfterHoursBars 行为。
 */
@ExtendWith(MockitoExtension.class)
class TigerOpenPythonBridgeAfterHoursTest {

    @Mock
    private PythonScriptExecutor pythonScriptExecutor;

    @Mock
    private TigerApiConfig tigerApiConfig;

    @Captor
    private ArgumentCaptor<Map<String, String>> envCaptor;

    @Captor
    private ArgumentCaptor<String> scriptCaptor;

    @Captor
    private ArgumentCaptor<String[]> argsCaptor;

    private ObjectMapper objectMapper;

    private TigerOpenPythonBridge bridge;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        TigerApiConfig.TigerCredentials credentials =
                new TigerApiConfig.TigerCredentials("test-id", "test-key", "test-account", "test-license", "PROD");
        lenient().when(tigerApiConfig.getCredentials()).thenReturn(credentials);

        bridge = new TigerOpenPythonBridge(pythonScriptExecutor, objectMapper, tigerApiConfig);
    }

    // ---- BRIDGE-001: fetchAfterHoursBars returns KLineData with sorted items ----

    @Test
    @DisplayName("BRIDGE-001: fetchAfterHoursBars parses JSON and sorts items newest first")
    void fetchAfterHoursBars_returnsSortedKLineData() throws Exception {
        String json = "{"
                + "\"symbol\":\"AAPL\","
                + "\"items\":["
                + "  {\"symbol\":\"AAPL\",\"time\":1719244800000,\"close\":150.0,\"volume\":1000000},"
                + "  {\"symbol\":\"AAPL\",\"time\":1719331200000,\"close\":151.0,\"volume\":1100000}"
                + "]}";
        lenient().when(pythonScriptExecutor.executeScriptWithEnvironment(
                anyMap(), anyString(), anyString(), anyString())).thenReturn(json);

        KLineData result = bridge.fetchAfterHoursBars("AAPL", 5);

        assertNotNull(result);
        assertEquals("AAPL", result.getSymbol());
        assertNotNull(result.getItems());
        // Items should be sorted newest first (1719331200000 first)
        assertEquals(2, result.getItems().size());
        assertEquals(151.0, result.getItems().get(0).getClose(), 0.001);
        assertEquals(150.0, result.getItems().get(1).getClose(), 0.001);

        verify(pythonScriptExecutor).executeScriptWithEnvironment(
                envCaptor.capture(), scriptCaptor.capture(), argsCaptor.capture());

        Map<String, String> env = envCaptor.getValue();
        assertTrue(env.containsKey("TIGEROPEN_TIGER_ID"));
        assertEquals("test-id", env.get("TIGEROPEN_TIGER_ID"));
        assertTrue(env.containsKey("TIGEROPEN_PRIVATE_KEY"));
        assertEquals("test-key", env.get("TIGEROPEN_PRIVATE_KEY"));

        assertEquals("tigeropen_channel.py", scriptCaptor.getValue());

        String[] args = argsCaptor.getValue();
        assertEquals("afterhours_bars", args[0]);
        assertEquals("AAPL", args[1]);
        assertEquals("5", args[2]);
    }

    // ---- BRIDGE-002: fetchAfterHoursBars returns null when no credentials ----

    @Test
    @DisplayName("BRIDGE-002: fetchAfterHoursBars returns null when credentials invalid")
    void fetchAfterHoursBars_noCredentials_returnsNull() throws Exception {
        TigerApiConfig.TigerCredentials invalidCreds =
                new TigerApiConfig.TigerCredentials("", "", "", "", "");
        when(tigerApiConfig.getCredentials()).thenReturn(invalidCreds);

        KLineData result = bridge.fetchAfterHoursBars("AAPL", 5);

        assertNull(result);
        verifyNoInteractions(pythonScriptExecutor);
    }

    @Test
    @DisplayName("BRIDGE-002b: fetchAfterHoursBars propagates script exception")
    void fetchAfterHoursBars_scriptException_propagates() throws Exception {
        lenient().when(pythonScriptExecutor.executeScriptWithEnvironment(
                anyMap(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Script failed"));

        assertThrows(RuntimeException.class, () -> bridge.fetchAfterHoursBars("AAPL", 5));
    }

    @Test
    @DisplayName("BRIDGE-002c: fetchAfterHoursBars with min barLimit (0)")
    void fetchAfterHoursBars_minBarLimit() throws Exception {
        String json = "{\"symbol\":\"AAPL\",\"items\":[]}";
        lenient().when(pythonScriptExecutor.executeScriptWithEnvironment(
                anyMap(), anyString(), anyString(), anyString()))
                .thenReturn(json);

        // barLimit < 1, code uses Math.max(1, barLimit) => 1
        KLineData result = bridge.fetchAfterHoursBars("AAPL", 0);

        assertNotNull(result);
        assertEquals("AAPL", result.getSymbol());
    }
}
