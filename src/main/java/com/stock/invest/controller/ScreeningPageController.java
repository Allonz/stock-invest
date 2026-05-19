package com.stock.invest.controller;

import com.stock.invest.enums.dto.ScreenerRunResponseDto;
import com.stock.invest.enums.dto.ScreeningResultDto;
import com.stock.invest.enums.dto.SnapshotGridViewDto;
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
import java.util.List;

@Controller
public class ScreeningPageController {

    private static final Logger log = LoggerFactory.getLogger(ScreeningPageController.class);

    private static final int DEFAULT_WINDOW_DAYS = 7;

    private final ScanOrchestratorService scanOrchestratorService;
    private final TigerSnapshotGridService tigerSnapshotGridService;
    private final DataGapFillerService dataGapFillerService;

    public ScreeningPageController(
            ScanOrchestratorService scanOrchestratorService,
            TigerSnapshotGridService tigerSnapshotGridService, DataGapFillerService dataGapFillerService) {
        this.scanOrchestratorService = scanOrchestratorService;
        this.tigerSnapshotGridService = tigerSnapshotGridService;
        this.dataGapFillerService = dataGapFillerService;
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
        ScreenerRunResponseDto runResult = scanOrchestratorService.runDailyScan(date, limit, days);
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
    @PostMapping("/api/debug/trigger-data-fill")
    @ResponseBody
    public String triggerDataFill() {
        log.info("[Manual] Triggering data fill...");
        dataGapFillerService.fillGaps();
        return "Data fill triggered. Check logs.";
    }

}