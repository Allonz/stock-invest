package com.stock.invest.controller;

import com.stock.invest.enums.dto.ApiResponse;
import com.stock.invest.entity.DataFillTask;
import com.stock.invest.repository.DataFillTaskRepository;
import com.stock.invest.service.DataFillProgressService;
import com.stock.invest.service.DataGapFillerService;
import com.stock.invest.repository.ScreeningMatchRepository;
import com.stock.invest.service.ScreeningProgressService;
import com.stock.invest.service.ScreeningProgressService.ScreeningProgress;
import com.stock.invest.service.ScreeningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final ScreeningService screeningService;
    private final DataGapFillerService dataGapFillerService;
    private final DataFillProgressService dataFillProgressService;
    private final DataFillTaskRepository dataFillTaskRepository;
    private final ScreeningMatchRepository screeningMatchRepository;
    private final ScreeningProgressService screeningProgressService;

    public AdminController(ScreeningService screeningService,
                           DataGapFillerService dataGapFillerService,
                           DataFillProgressService dataFillProgressService,
                           DataFillTaskRepository dataFillTaskRepository,
                           ScreeningMatchRepository screeningMatchRepository,
                           ScreeningProgressService screeningProgressService) {
        this.screeningService = screeningService;
        this.dataGapFillerService = dataGapFillerService;
        this.dataFillProgressService = dataFillProgressService;
        this.dataFillTaskRepository = dataFillTaskRepository;
        this.screeningMatchRepository = screeningMatchRepository;
        this.screeningProgressService = screeningProgressService;
    }

    @PostMapping("/trigger-screening")
    public ResponseEntity<ApiResponse<?>> triggerScreening(
            @RequestParam(value = "date", required = false) String date,
            @RequestParam(value = "limit", defaultValue = "20") Integer limit,
            @RequestParam(value = "windowDays", defaultValue = "7") Integer windowDays) {
        LocalDate targetDate = (date != null) ? LocalDate.parse(date) : ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalDate();
        log.info("[Admin] triggerScreening: date={}, limit={}, windowDays={}", targetDate, limit, windowDays);
        screeningService.runScreening(targetDate);
        return ResponseEntity.ok(ApiResponse.ok("Screening triggered. date=" + targetDate));
    }

    /**
     * POST /api/admin/trigger-screening-async — 异步全量筛选（windowDays=2,3,4,5,6,7, limit=TOTAL）
     */
    @PostMapping("/trigger-screening-async")
    public ResponseEntity<ApiResponse<?>> triggerScreeningAsync() {
        log.info("[Admin] triggerScreeningAsync: starting full async screening");
        List<Integer> windows = Arrays.asList(2, 3, 4, 5, 6, 7);
        int limit = Integer.MAX_VALUE;
        final String taskId = screeningProgressService.startScreening(windows, limit);
        final ScreeningProgress progress = screeningProgressService.getProgress(taskId);

        new Thread(() -> {
            LocalDate tradeDate = ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalDate();
            try {
                // Run screening ONCE — it processes all windows (2~7d) internally
                log.info("[Admin] async screening: starting (all windows 2~7d)");
                String batchId = screeningService.runScreening(tradeDate);

                // Query real matched counts per window from DB
                List<Object[]> counts = screeningMatchRepository.countByBatchIdGroupByWindowDays(batchId);
                java.util.Map<Integer, Long> countMap = new java.util.HashMap<>();
                for (Object[] row : counts) {
                    countMap.put((Integer) row[0], (Long) row[1]);
                }

                // Update progress windows with real data
                List<ScreeningProgressService.WindowProgress> windowList = progress.getWindows();
                int completed = 0;
                for (ScreeningProgressService.WindowProgress wp : windowList) {
                    wp.setStatus("DONE");
                    wp.setMatched(countMap.getOrDefault(wp.getDays(), 0L).intValue());
                    completed++;
                    progress.setCompletedWindows(completed);
                    log.info("[Admin] async screening: window {} day(s) done, matched={}", wp.getDays(), wp.getMatched());
                }
            } catch (Exception e) {
                log.error("[Admin] async screening failed", e);
            } finally {
                progress.setRunning(false);
            }
        }, "screening-async").start();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("message", "Full screening triggered async");
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    /**
     * POST /api/admin/run-screening-async — 异步高级筛选，入参 {limit, windowDays}
     */
    @PostMapping("/run-screening-async")
    public ResponseEntity<ApiResponse<?>> runScreeningAsync(@RequestBody Map<String, Object> params) {
        int limit = params.containsKey("limit") ? ((Number) params.get("limit")).intValue() : 60;
        int windowDays = params.containsKey("windowDays") ? ((Number) params.get("windowDays")).intValue() : 7;
        log.info("[Admin] runScreeningAsync: window={}, limit={}", windowDays, limit);

        List<Integer> windows = Arrays.asList(windowDays);
        final String taskId = screeningProgressService.startScreening(windows, limit);
        final ScreeningProgress progress = screeningProgressService.getProgress(taskId);

        new Thread(() -> {
            LocalDate tradeDate = ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalDate();
            try {
                List<ScreeningProgressService.WindowProgress> windowList = progress.getWindows();
                if (!windowList.isEmpty()) {
                    ScreeningProgressService.WindowProgress wp = windowList.get(0);
                    log.info("[Admin] async screening: starting window {} day(s)", wp.getDays());
                    String batchId = screeningService.runScreening(tradeDate);
                    // Query real matched count for this window
                    long realMatched = screeningMatchRepository.countByBatchIdGroupByWindowDays(batchId).stream()
                        .filter(r -> r[0].equals(wp.getDays()))
                        .mapToLong(r -> (Long) r[1])
                        .findFirst().orElse(0L);
                    wp.setStatus("DONE");
                    wp.setMatched((int) realMatched);
                    progress.setCompletedWindows(1);
                }
            } catch (Exception e) {
                log.error("[Admin] async advanced screening failed", e);
            } finally {
                progress.setRunning(false);
            }
        }, "screening-advanced-async").start();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("message", "Advanced screening triggered async");
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    /**
     * GET /api/admin/screening-progress?taskId=xxx
     */
    @GetMapping("/screening-progress")
    public ResponseEntity<ApiResponse<?>> getScreeningProgress(@RequestParam("taskId") String taskId) {
        ScreeningProgress p = screeningProgressService.getProgress(taskId);
        if (p == null) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "running", false,
                    "windows", List.of(),
                    "totalWindows", 0,
                    "completedWindows", 0,
                    "elapsedSeconds", 0L,
                    "startTime", 0L
            )));
        }
        return ResponseEntity.ok(ApiResponse.ok(p.toMap()));
    }

    /**
     * 异步触发数据补缺。立即返回 taskId，后台线程执行 fillGaps。
     */
    @PostMapping("/trigger-data-fill")
    public ResponseEntity<ApiResponse<?>> triggerDataFill() {
        log.info("[Admin] triggerDataFill: manual trigger (async)");

        DataFillProgressService.FillProgress progress = dataFillProgressService.startFill();
        progress.setRunning(true);
        progress.setStartTime(System.currentTimeMillis());

        // 在新线程中异步执行 fillGaps
        new Thread(() -> {
            try {
                dataGapFillerService.fillGaps();
            } catch (Exception e) {
                log.error("[Admin] async fillGaps failed", e);
            } finally {
                progress.setStage("COMPLETED");
                progress.setRunning(false);
            }
        }, "fillGaps-async").start();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", "manual");
        data.put("message", "Data fill triggered");
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    @PostMapping("/trigger-retry-tasks")
    public ResponseEntity<ApiResponse<?>> triggerRetryTasks() {
        log.info("[Admin] triggerRetryTasks: manual trigger");
        new Thread(() -> {
            try { dataGapFillerService.processRetryingTasks(); }
            catch (Exception e) { log.error("[Admin] processRetryingTasks failed", e); }
        }, "processRetry-async").start();
        return ResponseEntity.ok(ApiResponse.ok(Map.of("message", "Retry tasks triggered")));
    }

    /**
     * GET /api/admin/data-fill-progress
     * 返回当前异步补缺的进度。
     */
    @GetMapping("/data-fill-progress")
    public ResponseEntity<ApiResponse<?>> getDataFillProgress() {
        DataFillProgressService.FillProgress p = dataFillProgressService.getProgress();
        if (p == null) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "running", false,
                    "stage", "IDLE",
                    "totalSymbols", 0,
                    "processedSymbols", 0,
                    "gapsFound", 0,
                    "filled", 0,
                    "failed", 0,
                    "elapsedSeconds", 0L,
                    "startTime", 0L
            )));
        }
        return ResponseEntity.ok(ApiResponse.ok(p.toMap()));
    }

    /**
     * GET /api/admin/fill-tasks
     * 返回补缺任务列表，支持按 status 过滤和分页。
     */
    @GetMapping("/fill-tasks")
    public ResponseEntity<ApiResponse<?>> getFillTasks(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "symbol", required = false) String symbol,
            @RequestParam(value = "tradeDate", required = false) String tradeDateStr,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "sortOrder", defaultValue = "desc") String sortOrder) {

        LocalDate tradeDate = null;
        if (tradeDateStr != null && !tradeDateStr.trim().isEmpty()) {
            try {
                tradeDate = LocalDate.parse(tradeDateStr.trim());
            } catch (Exception e) {
                tradeDate = null;
            }
        }

        // 白名单校验，只允许按已知字段排序
        String sortField = "createdAt";
        if (sortBy != null) {
            switch (sortBy) {
                case "symbol":
                case "tradeDate":
                case "status":
                case "retryCount":
                case "id":
                    sortField = sortBy;
                    break;
                default:
                    sortField = "createdAt";
            }
        }

        Sort.Direction direction = "asc".equalsIgnoreCase(sortOrder) ? Sort.Direction.ASC : Sort.Direction.DESC;
        PageRequest pageRequest = PageRequest.of(
                Math.max(0, page - 1),
                size,
                Sort.by(direction, sortField)
        );

        Page<DataFillTask> taskPage = dataFillTaskRepository.findByFilters(
                symbol,
                tradeDate,
                status,
                pageRequest);

        var data = taskPage.getContent().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("symbol", t.getSymbol());
            m.put("tradeDate", t.getTradeDate().toString());
            m.put("status", t.getStatus());
            m.put("retryCount", t.getRetryCount());
            m.put("maxRetries", t.getMaxRetries());
            m.put("lastError", t.getLastError());
            m.put("createdAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : null);
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", taskPage.getTotalElements());
        result.put("page", page);
        result.put("size", size);
        result.put("data", data);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * GET /api/admin/fill-task-count
     * 按 status 分组统计补缺任务数量。
     */
    @GetMapping("/fill-task-count")
    public ResponseEntity<ApiResponse<?>> getFillTaskCount() {
        long total = dataFillTaskRepository.count();
        long retrying = dataFillTaskRepository.countByStatus("retrying");
        long completed = dataFillTaskRepository.countByStatus("completed");
        long stopped = dataFillTaskRepository.countByStatus("stopped");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("retrying", retrying);
        result.put("completed", completed);
        result.put("stopped", stopped);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * POST /api/admin/run-screening 完整版筛选
     */
    @PostMapping("/run-screening")
    public ResponseEntity<ApiResponse<?>> runScreening(
            @RequestParam(value = "date", required = false) String date,
            @RequestParam(value = "limit", defaultValue = "50") Integer limit,
            @RequestParam(value = "windowDays", defaultValue = "2") Integer windowDays
    ) {
        LocalDate tradeDate = (date == null || date.trim().isEmpty())
                ? ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalDate()
                : LocalDate.parse(date);
        log.info("[Admin] runScreening: date={}, limit={}, windowDays={}", tradeDate, limit, windowDays);
        try {
            String batchId = screeningService.runScreening(tradeDate);
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "message", "Screening completed",
                    "batchId", batchId != null ? batchId : ""
            )));
        } catch (Exception e) {
            log.error("[Admin] runScreening failed", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Screening failed: " + e.getMessage()));
        }
    }

}
