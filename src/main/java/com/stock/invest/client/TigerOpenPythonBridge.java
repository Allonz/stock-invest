package com.stock.invest.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.model.KLineData;
import com.stock.invest.util.PythonScriptExecutor;
import com.stock.invest.util.KLineDataUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通过本地 venv 中的 tigeropen（Python SDK）拉取行情：市场扫描与日线 K 线。
 * 凭证与 Java Tiger SDK 共用 application.yml 中的 tiger.api.*，经环境变量传入子进程。
 */
@Component
public class TigerOpenPythonBridge {

    private static final String SCRIPT_REL = "src/main/resources/python/tigeropen_channel.py";

    private final PythonScriptExecutor pythonScriptExecutor;
    private final ObjectMapper objectMapper;

    @Value("${tiger.api.tiger_id:}")
    private String tigerId;

    @Value("${tiger.api.private_key:}")
    private String privateKey;

    @Value("${tiger.api.account:}")
    private String account;

    @Value("${tiger.api.license:}")
    private String license;

    public TigerOpenPythonBridge(PythonScriptExecutor pythonScriptExecutor, ObjectMapper objectMapper) {
        this.pythonScriptExecutor = pythonScriptExecutor;
        this.objectMapper = objectMapper;
    }

    public boolean hasCredentials() {
        return tigerId != null && !tigerId.trim().isEmpty()
                && privateKey != null && !privateKey.trim().isEmpty()
                && account != null && !account.trim().isEmpty();
    }

    public List<String> listCandidates(int limit, double minPrice, double maxPrice) throws Exception {
        if (!hasCredentials()) {
            return Collections.emptyList();
        }
        String json = pythonScriptExecutor.executeScriptWithEnvironment(
                tigerEnv(),
                scriptPath(),
                "scan",
                String.valueOf(Math.max(1, limit)),
                String.valueOf(minPrice),
                String.valueOf(maxPrice)
        );
        return objectMapper.readValue(json.trim(), new TypeReference<List<String>>() { });
    }

    public KLineData fetchDailyBars(String symbol, int barLimit) throws Exception {
        if (!hasCredentials()) {
            return null;
        }
        String json = pythonScriptExecutor.executeScriptWithEnvironment(
                tigerEnv(),
                scriptPath(),
                "bars",
                symbol,
                String.valueOf(Math.max(7, barLimit))
        );
        KLineData data = objectMapper.readValue(json.trim(), KLineData.class);
        if (data != null && data.getItems() != null) {
            KLineDataUtils.sortItemsNewestFirst(data);
        }
        return data;
    }

    private String scriptPath() {
        return Paths.get(System.getProperty("user.dir", ".")).resolve(SCRIPT_REL).toString();
    }

    private Map<String, String> tigerEnv() {
        Map<String, String> env = new HashMap<String, String>();
        if (tigerId != null && !tigerId.trim().isEmpty()) {
            env.put("TIGEROPEN_TIGER_ID", tigerId.trim());
        }
        if (account != null && !account.trim().isEmpty()) {
            env.put("TIGEROPEN_ACCOUNT", account.trim());
        }
        if (privateKey != null && !privateKey.trim().isEmpty()) {
            env.put("TIGEROPEN_PRIVATE_KEY", privateKey);
        }
        if (license != null && !license.trim().isEmpty()) {
            env.put("TIGEROPEN_LICENSE", license.trim());
        }
        return env;
    }
}
