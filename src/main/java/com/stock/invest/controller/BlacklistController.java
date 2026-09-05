package com.stock.invest.controller;

import com.stock.invest.dto.ApiResponse;
import com.stock.invest.dto.SymbolBlacklistDto;
import com.stock.invest.entity.SymbolBlacklist;
import com.stock.invest.service.SymbolBlacklistService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/blacklist")
public class BlacklistController {

    private final SymbolBlacklistService blacklistService;

    public BlacklistController(SymbolBlacklistService blacklistService) {
        this.blacklistService = blacklistService;
    }

    /**
     * 获取所有黑名单记录（P3-5：返回 DTO，不直接暴露实体；评审 A-1：统一 ApiResponse 包装）。
     */
    @GetMapping("/list")
    public ApiResponse<List<SymbolBlacklistDto>> list() {
        return ApiResponse.ok(blacklistService.listActiveEntries().stream()
                .map(BlacklistController::toDto)
                .toList());
    }

    private static SymbolBlacklistDto toDto(SymbolBlacklist e) {
        return new SymbolBlacklistDto(
                e.getId(),
                e.getSymbol(),
                e.getConsecutive404Count(),
                e.getFirst404Date(),
                e.getLast404Date(),
                e.getSourceErrors(),
                e.getStatus(),
                e.getUpdatedAt()
        );
    }

    /**
     * 手动清理一条黑名单记录（解除黑名单）。
     */
    @PostMapping("/clear")
    public ApiResponse<Map<String, Object>> clear(@RequestParam String symbol) {
        blacklistService.clearSymbol(symbol);
        return ApiResponse.ok(Map.of("status", "ok", "symbol", symbol));
    }
}
