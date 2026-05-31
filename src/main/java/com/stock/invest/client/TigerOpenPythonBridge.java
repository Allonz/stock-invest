package com.stock.invest.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.config.TigerApiConfig;
import com.stock.invest.config.TigerApiConfig.TigerCredentials;
import com.stock.invest.model.KLineData;
import com.stock.invest.util.KLineDataUtils;
import com.stock.invest.util.PythonScriptExecutor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通过本地 venv 中的 tigeropen（Python SDK）拉取行情：市场扫描与日线 K 线。
 * <p>
 * 凭证从 {@link TigerApiConfig#getCredentials()} 统一获取，经 {@link #buildEnv()} 以环境变量传入子进程。
 * </p>
 */
@Component
public class TigerOpenPythonBridge {

    private static final String PYTHON_SCRIPT = "tigeropen_channel.py";

    private final PythonScriptExecutor pythonScriptExecutor;
    private final ObjectMapper objectMapper;
    private final TigerApiConfig tigerApiConfig;

    public TigerOpenPythonBridge(PythonScriptExecutor pythonScriptExecutor,
                                 ObjectMapper objectMapper,
                                 TigerApiConfig tigerApiConfig) {
        this.pythonScriptExecutor = pythonScriptExecutor;
        this.objectMapper = objectMapper;
        this.tigerApiConfig = tigerApiConfig;
    }

    public boolean hasCredentials() {
        return tigerApiConfig.getCredentials().isValid();
    }

    /**
     * 构建 Tiger 凭证环境变量 Map，用于传入 Python 子进程。
     */
    public Map<String, String> buildEnv() {
        TigerCredentials creds = tigerApiConfig.getCredentials();
        Map<String, String> env = new HashMap<>();
        env.put("TIGEROPEN_TIGER_ID", creds.tigerId());
        env.put("TIGEROPEN_PRIVATE_KEY", creds.privateKey());
        if (!creds.account().isEmpty()) {
            env.put("TIGEROPEN_ACCOUNT", creds.account());
        }
        if (!creds.license().isEmpty()) {
            env.put("TIGEROPEN_LICENSE", creds.license());
        }
        return env;
    }

    /**
     * 执行 {@code tigeropen_channel.py} 中的某个命令，自动注入 Tiger 凭证环境变量。
     * <p>
     * Python 脚本通过 {@link PythonScriptExecutor} 从 classpath 加载，因此只需传脚本文件名即可。
     * </p>
     *
     * @param action 命令名（scan/bars/calendar/get_stock_info/get_daily_kline/get_batch_kline）
     * @param args   命令参数
     * @return 脚本标准输出字符串
     * @throws Exception 脚本执行失败时抛出
     */
    public String executePythonScript(String action, String... args) throws Exception {
        if (!hasCredentials()) {
            throw new IllegalStateException("Tiger credentials not configured");
        }
        // 将 action 和 args 合并为一个 String 数组
        String[] allArgs = new String[1 + args.length];
        allArgs[0] = action;
        System.arraycopy(args, 0, allArgs, 1, args.length);
        return pythonScriptExecutor.executeScriptWithEnvironment(
                buildEnv(),
                PYTHON_SCRIPT,
                allArgs);
    }

    public List<String> listCandidates(int limit, double minPrice, double maxPrice) throws Exception {
        if (!hasCredentials()) {
            return Collections.emptyList();
        }
        String json = executePythonScript("scan",
                String.valueOf(Math.max(1, limit)),
                String.valueOf(minPrice),
                String.valueOf(maxPrice));
        return objectMapper.readValue(json.trim(), new TypeReference<List<String>>() {});
    }

    public KLineData fetchDailyBars(String symbol, int barLimit) throws Exception {
        if (!hasCredentials()) {
            return null;
        }
        String json = executePythonScript("bars",
                symbol,
                String.valueOf(Math.max(7, barLimit)));
        KLineData data = objectMapper.readValue(json.trim(), KLineData.class);
        if (data != null && data.getItems() != null) {
            KLineDataUtils.sortItemsNewestFirst(data);
        }
        return data;
    }
}
