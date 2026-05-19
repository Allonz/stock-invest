package com.stock.invest.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.model.KLineData;
import com.stock.invest.util.PythonScriptExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Tiger 行情 Python 桥接服务。
 * <p>
 * 通过 {@link PythonScriptExecutor} 调用 Python 脚本获取 Tiger OpenAPI K 线数据。
 * 作为 DataGapFillerService fallback 链的第二级。
 * </p>
 */
@Service
public class TigerPythonService {

    private static final Logger log = LoggerFactory.getLogger(TigerPythonService.class);

    private static final String TIGER_PYTHON_SCRIPT = "tigeropen_channel.py";

    private final PythonScriptExecutor pythonScriptExecutor;
    private final ObjectMapper objectMapper;

    public TigerPythonService(PythonScriptExecutor pythonScriptExecutor, ObjectMapper objectMapper) {
        this.pythonScriptExecutor = pythonScriptExecutor;
        this.objectMapper = objectMapper;
    }

    /**
     * 通过 Tiger Python 桥获取单只股票的日 K 线数据。
     *
     * @param symbol 股票代码
     * @return KLineData 对象，失败时返回空的 KLineData
     */
    public KLineData fetchDailyKLine(String symbol) {
        try {
            String result = pythonScriptExecutor.executeScript(
                    TIGER_PYTHON_SCRIPT,
                    "get_daily_kline",
                    symbol);
            return objectMapper.readValue(result, KLineData.class);
        } catch (Exception e) {
            log.warn("TigerPythonService fetchDailyKLine failed for {}: {}", symbol, e.getMessage());
            return new KLineData();
        }
    }

    /**
     * 通过 Tiger Python 桥获取多只股票 K 线数据。
     *
     * @param symbols 股票代码列表
     * @param period  周期（day/week/month）
     * @param count   条数
     * @return KLineData 列表
     */
    public String fetchBatchKLine(java.util.List<String> symbols, String period, int count) {
        try {
            String symbolsStr = String.join(",", symbols);
            return pythonScriptExecutor.executeScript(
                    TIGER_PYTHON_SCRIPT,
                    "get_batch_kline",
                    symbolsStr,
                    period,
                    String.valueOf(count));
        } catch (Exception e) {
            log.warn("TigerPythonService fetchBatchKLine failed: {}", e.getMessage());
            return "[]";
        }
    }
}
