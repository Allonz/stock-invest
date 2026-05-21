package com.stock.invest.controller;

import com.stock.invest.entity.ScreeningMatch;
import com.stock.invest.enums.dto.ScreenerRunResponseDto;
import com.stock.invest.enums.dto.ScreeningResultDto;
import com.stock.invest.enums.dto.SnapshotGridViewDto;
import com.stock.invest.repository.ScreeningMatchRepository;
import com.stock.invest.service.ScanOrchestratorService;
import com.stock.invest.service.TigerSnapshotGridService;
import com.stock.invest.service.DataGapFillerService;
import org.springframework.web.bind.annotation.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
public class ScreeningPageController {

    private static final Logger log = LoggerFactory.getLogger(ScreeningPageController.class);

    private static final int DEFAULT_WINDOW_DAYS = 7;
    private static final List<Integer> WINDOW_DAYS = List.of(3, 4, 5, 6, 7);

    private final ScanOrchestratorService scanOrchestratorService;
    private final TigerSnapshotGridService tigerSnapshotGridService;
    private final DataGapFillerService dataGapFillerService;
    private final ScreeningMatchRepository screeningMatchRepository;

    public ScreeningPageController(
            ScanOrchestratorService scanOrchestratorService,
            TigerSnapshotGridService tigerSnapshotGridService,
            DataGapFillerService dataGapFillerService,
            ScreeningMatchRepository screeningMatchRepository) {
        this.scanOrchestratorService = scanOrchestratorService;
        this.tigerSnapshotGridService = tigerSnapshotGridService;
        this.dataGapFillerService = dataGapFillerService;
        this.screeningMatchRepository = screeningMatchRepository;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/screener/daily";
    }

    @GetMapping("/page/screener")
    public String pageScreener() {
        return "redirect:/screener/daily";
    }

    @GetMapping("/screener/daily")
    public String daily(
            @RequestParam(value = "tradeDate", required = false) String tradeDate,
            @RequestParam(value = "minPrice", required = false) Double minPrice,
            @RequestParam(value = "maxPrice", required = false) Double maxPrice,
            @RequestParam(value = "windowDays", required = false, defaultValue = "7") Integer windowDays,
            @RequestParam(value = "notice", required = false) String notice,
            Model model
    ) {
        LocalDate date = tradeDate == null || tradeDate.trim().isEmpty() ? LocalDate.now() : LocalDate.parse(tradeDate);
        List<ScreeningResultDto> rows = scanOrchestratorService.queryByDate(date, minPrice, maxPrice);
        int days = sanitizeWindowDays(windowDays);
        model.addAttribute("tradeDate", date.toString());
        model.addAttribute("minPrice", minPrice);
        model.addAttribute("maxPrice", maxPrice);
        model.addAttribute("windowDays", days);
        model.addAttribute("notice", notice);
        model.addAttribute("rows", rows);
        SnapshotGridViewDto snapshotGrid = tigerSnapshotGridService.buildGrid(48);
        model.addAttribute("snapshotGrid", snapshotGrid);
        return "screener-daily";
    }

    @PostMapping("/screener/run")
    public String run(
            @RequestParam(value = "tradeDate", required = false) String tradeDate,
            @RequestParam(value = "limit", defaultValue = "20") Integer limit,
            @RequestParam(value = "windowDays", required = false, defaultValue = "7") Integer windowDays,
            RedirectAttributes redirectAttributes
    ) {
        LocalDate date = tradeDate == null || tradeDate.trim().isEmpty() ? LocalDate.now() : LocalDate.parse(tradeDate);
        int days = sanitizeWindowDays(windowDays);
        ScreenerRunResponseDto runResult = scanOrchestratorService.runDailyScanFromSnapshotImport(date, limit, days);
        String notice = "已执行筛选: processed="
                + runResult.processedStocks()
                + ", matched="
                + runResult.matchedStocks()
                + ", windowDays="
                + days
                + ", batchId="
                + runResult.batchId();
        redirectAttributes.addAttribute("tradeDate", date.toString());
        redirectAttributes.addAttribute("windowDays", days);
        redirectAttributes.addAttribute("notice", notice);
        return "redirect:/screener/daily";
    }

    private static int sanitizeWindowDays(Integer windowDays) {
        if (windowDays == null) {
            return DEFAULT_WINDOW_DAYS;
        }
        if (windowDays < 3) {
            return 3;
        }
        if (windowDays > 7) {
            return 7;
        }
        return windowDays;
    }

    @GetMapping("/page/screening")
    public String screeningPage(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String batchId,
            Model model
    ) {
        List<Map<String, Object>> batchHistory = new ArrayList<>();
        List<Object[]> summaries = screeningMatchRepository.findBatchSummary();
        for (Object[] row : summaries) {
            Map<String, Object> item = Map.of(
                    "batchId", row[0],
                    "matchCount", row[1],
                    "lastTradeDate", row[2] != null ? row[2].toString() : ""
            );
            batchHistory.add(item);
        }
        model.addAttribute("batchHistory", batchHistory);

        // 当前批次详情
        String currentBatchId;

        if (batchId != null && !batchId.isBlank()) {
            currentBatchId = batchId;
        } else if (!batchHistory.isEmpty()) {
            currentBatchId = (String) batchHistory.get(0).get("batchId");
        } else {
            currentBatchId = null;
        }

        // 按 windowDays 分组获取筛选结果
        Map<String, List<ScreeningMatch>> windowResults = new LinkedHashMap<>();
        Map<String, Integer> totalHits = new LinkedHashMap<>();

        if (currentBatchId != null) {
            for (int wd : WINDOW_DAYS) {
                String key = wd + "d";
                List<ScreeningMatch> matches = screeningMatchRepository
                        .findByBatchIdAndWindowDaysOrderByIdAsc(currentBatchId, wd);

                // 如果指定了 symbol 过滤
                if (symbol != null && !symbol.isBlank()) {
                    String finalSymbol = symbol;
                    matches = matches.stream()
                            .filter(m -> m.getSymbol().contains(finalSymbol))
                            .toList();
                }

                windowResults.put(key, matches);
                totalHits.put(key, matches.size());
            }
        }

        model.addAttribute("currentBatchId", currentBatchId);
        model.addAttribute("windowResults", windowResults);
        model.addAttribute("totalHits", totalHits);
        model.addAttribute("filterSymbol", symbol);

        return "screening";
    }

    @PostMapping("/api/debug/trigger-data-fill")
    @ResponseBody
    public String triggerDataFill() {
        log.info("[Manual] Triggering data fill...");
        dataGapFillerService.fillGaps();
        return "Data fill triggered. Check logs.";
    }

}