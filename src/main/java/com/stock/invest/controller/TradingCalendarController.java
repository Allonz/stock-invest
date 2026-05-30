package com.stock.invest.controller;

import com.stock.invest.enums.dto.ApiResponse;
import com.stock.invest.service.TigerTradeCalendarService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/trading-calendar")
public class TradingCalendarController {

    private static final Logger log = LoggerFactory.getLogger(TradingCalendarController.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ZoneId NY_ZONE = ZoneId.of("America/New_York");

    private final TigerTradeCalendarService tigerTradeCalendarService;

    public TradingCalendarController(TigerTradeCalendarService tigerTradeCalendarService) {
        this.tigerTradeCalendarService = tigerTradeCalendarService;
    }

    @GetMapping("/is-open")
    public ResponseEntity<ApiResponse<Map<String, Object>>> isOpen(
            @RequestParam(value = "date", required = false) String dateParam,
            @RequestParam(value = "exchange", required = false, defaultValue = "XNYS") String exchange) {

        try {
            LocalDate queryDate;
            if (dateParam != null && !dateParam.trim().isEmpty()) {
                queryDate = LocalDate.parse(dateParam.trim(), DATE_FMT);
            } else {
                queryDate = LocalDate.now(NY_ZONE);
            }

            boolean isOpen = tigerTradeCalendarService.isTradingDay(queryDate);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("date", queryDate.format(DATE_FMT));
            data.put("isOpen", isOpen);
            data.put("exchange", exchange);
            data.put("timezone", "America/New_York");
            data.put("source", "tiger");

            return ResponseEntity.ok(ApiResponse.ok(data));

        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Invalid date format, expected yyyy-MM-dd", "INVALID_DATE"));
        } catch (Exception e) {
            log.error("[TradingCalendarController] is-open failed", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Internal error: " + e.getMessage()));
        }
    }
}
