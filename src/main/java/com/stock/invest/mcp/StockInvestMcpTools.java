package com.stock.invest.mcp;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.stock.invest.enums.dto.TigerWatchlistIngestRequestDto;
import com.stock.invest.enums.dto.TigerWatchlistIngestResponseDto;
import com.stock.invest.enums.dto.TigerWatchlistRowDto;
import com.stock.invest.security.IngestApiGuard;
import com.stock.invest.service.OrchestrationService;
import com.stock.invest.service.ScreeningService;
import com.stock.invest.service.TigerWatchlistIngestService;
import com.stock.invest.service.TradingCalendarDbService;

/**
 * 将老虎截图导入 / 开盘日历 / 股票筛选 / 串行编排暴露为 MCP Tools，供 Hermes / OpenClaw 调用。
 *
 * <p>端点：http://127.0.0.1:8090/api/mcp (Streamable HTTP)</p>
 */
@Component
public class StockInvestMcpTools {

    private static final ZoneId NY_ZONE = ZoneId.of("America/New_York");
    private static final String DEFAULT_MARKET = "US";

    private final TigerWatchlistIngestService ingestService;
    private final TradingCalendarDbService calendarDbService;
    private final ScreeningService screeningService;
    private final OrchestrationService orchestrationService;
    private final IngestApiGuard ingestApiGuard;

    public StockInvestMcpTools(TigerWatchlistIngestService ingestService,
                               TradingCalendarDbService calendarDbService,
                               ScreeningService screeningService,
                               OrchestrationService orchestrationService,
                               IngestApiGuard ingestApiGuard) {
        this.ingestService = ingestService;
        this.calendarDbService = calendarDbService;
        this.screeningService = screeningService;
        this.orchestrationService = orchestrationService;
        this.ingestApiGuard = ingestApiGuard;
    }

    /* ---------- 1. 老虎截图导入 ---------- */

    @McpTool(name = "tiger_import_watchlist",
             description = "导入老虎证券自选股截图 OCR 结果，按 tradeDate upsert 到 stock_daily_bar")
    public TigerWatchlistIngestResponseDto importTigerWatchlist(
            @McpToolParam(description = "交易日 ISO 日期，如 2026-08-03", required = true) String tradeDate,
            @McpToolParam(description = "截图识别出的行列表（symbol/code、lastPrice/closePrice 必填，volume 支持 7.33万 等）",
                          required = true) List<TigerWatchlistRowDto> rows,
            @McpToolParam(description = "仅当后端配置了 ingest.api-key 时需要", required = false) String apiKey) {
        ingestApiGuard.verifyOptionalKey(apiKey);
        return ingestService.ingest(new TigerWatchlistIngestRequestDto(tradeDate, rows));
    }

    /* ---------- 2. 开盘日历 ---------- */

    @McpTool(name = "trading_calendar_is_open",
             description = "查询指定日期是否开盘，缺省为纽约时间今天、美股")
    public Map<String, Object> isTradingDay(
            @McpToolParam(description = "日期 yyyy-MM-dd", required = false) String date,
            @McpToolParam(description = "交易所 XNYS/XNAS/XHKG/XSHG，缺省 XNYS", required = false) String exchange) {
        LocalDate queryDate = (date == null || date.isBlank())
                ? LocalDate.now(NY_ZONE) : LocalDate.parse(date);
        String market = resolveMarket(exchange);
        Boolean isOpen = calendarDbService.isTradingDay(market, queryDate);
        if (isOpen == null) {
            isOpen = true; // 与 REST 控制器一致：数据源全挂时默认开盘
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", queryDate.toString());
        data.put("isOpen", isOpen);
        data.put("exchange", exchange == null ? "XNYS" : exchange);
        data.put("market", market);
        return data;
    }

    @McpTool(name = "trading_calendar_list", description = "获取整年交易日历列表")
    public List<Map<String, Object>> tradingCalendarList(
            @McpToolParam(description = "年份，缺省今年", required = false) Integer year,
            @McpToolParam(description = "市场 US/HK/CN，缺省 US", required = false) String market) {
        int targetYear = (year == null) ? LocalDate.now(NY_ZONE).getYear() : year;
        return calendarDbService.getYearCalendar(market == null ? DEFAULT_MARKET : market, targetYear)
                .stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tradeDate", e.getTradeDate().toString());
                    m.put("isOpen", e.getIsOpen());
                    m.put("market", e.getMarket());
                    m.put("source", e.getSource());
                    return m;
                })
                .toList();
    }

    /* ---------- 3. 股票筛选（NotificationController 同款 service） ---------- */

    @McpTool(name = "screening_notification_latest",
             description = "获取最新筛选结果通知，按 algorithm + windowDays 分组（对应 GET /api/notification/latest）")
    public Map<String, Object> screeningNotificationLatest(
            @McpToolParam(description = "逗号分隔的窗口列表，如 \"2d,3d,4d,5d\"；缺省返回全部窗口", required = false) String windows) {
        return screeningService.getLatestNotificationGrouped(windows);
    }

    @McpTool(name = "screening_history",
             description = "获取历史筛选批次列表（对应 GET /api/notification/history）")
    public List<Map<String, Object>> screeningHistory() {
        return screeningService.getScreeningHistory(100);
    }

    @McpTool(name = "screening_batch_detail",
             description = "获取某批次筛选详情（对应 GET /api/notification/batch/{batchId}）")
    public Map<String, Object> screeningBatchDetail(
            @McpToolParam(description = "筛选批次 UUID", required = true) String batchId) {
        return screeningService.getBatchDetail(batchId);
    }

    @McpTool(name = "screening_run",
             description = "手动触发一次模式筛选，返回 batchId（日期缺省为纽约时间今天；windowDays 缺省或小于2时全窗口 2~7 天）")
    public Map<String, Object> runScreening(
            @McpToolParam(description = "交易日 yyyy-MM-dd", required = false) String date,
            @McpToolParam(description = "窗口天数（2~7），缺省全窗口", required = false) Integer windowDays,
            @McpToolParam(description = "最多评估的候选 symbol 数，缺省不限", required = false) Integer limit) {
        LocalDate tradeDate = (date == null || date.isBlank())
                ? LocalDate.now(NY_ZONE) : LocalDate.parse(date);
        String batchId = screeningService.runScreening(tradeDate, windowDays, limit);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchId", batchId);
        data.put("tradeDate", tradeDate.toString());
        return data;
    }

    /* ---------- 4. 串行编排（对应 POST /api/orchestration/step） ---------- */

    @McpTool(name = "orchestration_trigger",
             description = "触发串行编排链中的任意一步：history_backfill（历史补缺）/ day_backfill（当日补缺）/ screening（筛选）。"
                     + "chain=true（默认）按 nextStep 自动串后续步骤（history_backfill→day_backfill→screening→report）；"
                     + "chain=false 单步执行，完成后不触发下一步。webhook_url 缺省为 Hermes 本机 8645 接力端点。"
                     + "返回 run_id 与受理状态，后续每一步完成会经 webhook 回调自动接力。")
    public Map<String, Object> orchestrationTrigger(
            @McpToolParam(description = "要触发的步骤：history_backfill / day_backfill / screening", required = true) String step,
            @McpToolParam(description = "链路标识 run_id；缺省自动生成 manual-<timestamp>", required = false) String runId,
            @McpToolParam(description = "交易日 yyyy-MM-dd；缺省为纽约时间今天", required = false) String tradeDate,
            @McpToolParam(description = "本 run 的回调 webhook 端点；缺省 http://localhost:8645/webhooks/tiger-orch", required = false) String webhookUrl,
            @McpToolParam(description = "true=自动接力后续步骤（默认）；false=单步执行不接力", required = false) Boolean chain) {
        if (step == null || step.isBlank()) {
            throw new IllegalArgumentException("step is required: history_backfill / day_backfill / screening");
        }
        if (!"history_backfill".equals(step) && !"day_backfill".equals(step) && !"screening".equals(step)) {
            throw new IllegalArgumentException("step must be one of: history_backfill, day_backfill, screening");
        }
        String effRunId = (runId == null || runId.isBlank())
                ? "manual-" + System.currentTimeMillis() : runId;
        String effTradeDate = (tradeDate == null || tradeDate.isBlank())
                ? LocalDate.now(NY_ZONE).toString() : tradeDate;
        String effWebhookUrl = (webhookUrl == null || webhookUrl.isBlank())
                ? "http://localhost:8645/webhooks/tiger-orch" : webhookUrl;
        boolean effChain = chain == null || chain;

        orchestrationService.triggerStep(step, effRunId, effTradeDate, effWebhookUrl, effChain);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("step", step);
        data.put("run_id", effRunId);
        data.put("trade_date", effTradeDate);
        data.put("chain", effChain);
        data.put("accepted", true);
        data.put("message", effChain
                ? "步骤已受理，异步执行中；后续步骤经 webhook 自动接力"
                : "步骤已受理，异步执行中（单步模式，不接力后续步骤）");
        return data;
    }

    private static String resolveMarket(String exchange) {
        if (exchange == null) {
            return DEFAULT_MARKET;
        }
        String upper = exchange.toUpperCase();
        if (upper.startsWith("XNYS") || upper.startsWith("XNAS")
                || upper.equals("US") || upper.startsWith("ARCX")) {
            return DEFAULT_MARKET;
        }
        if (upper.startsWith("XHKG") || upper.equals("HK")) {
            return "HK";
        }
        if (upper.startsWith("XSHG") || upper.startsWith("XSHE") || upper.equals("CN")) {
            return "CN";
        }
        return DEFAULT_MARKET;
    }
}
