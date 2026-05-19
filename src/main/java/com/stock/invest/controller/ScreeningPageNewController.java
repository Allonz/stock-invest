package com.stock.invest.controller;

import com.stock.invest.entity.ScreeningMatch;
import com.stock.invest.repository.ScreeningMatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
public class ScreeningPageNewController {

    private static final Logger log = LoggerFactory.getLogger(ScreeningPageNewController.class);

    private static final List<Integer> WINDOW_DAYS = List.of(2, 3, 4, 5, 6, 7);

    private final ScreeningMatchRepository screeningMatchRepository;

    public ScreeningPageNewController(ScreeningMatchRepository screeningMatchRepository) {
        this.screeningMatchRepository = screeningMatchRepository;
    }

    @GetMapping("/page/screening")
    public String screening(
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
}
