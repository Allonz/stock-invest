package com.stock.invest.controller;

import com.stock.invest.entity.SymbolBlacklist;
import com.stock.invest.enums.dto.ApiResponse;
import com.stock.invest.enums.dto.SymbolBlacklistDto;
import com.stock.invest.service.SymbolBlacklistService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
     * 获取所有黑名单记录（P3-5：返回 DTO，不直接暴露实体）
     */
    @GetMapping("/list")
    public List<SymbolBlacklistDto> list() {
        return blacklistService.listActiveEntries().stream()
                .map(BlacklistController::toDto)
                .toList();
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
     * 手动清理一条黑名单记录（解除黑名单）
     */
    @PostMapping("/clear")
    public ResponseEntity<?> clear(@RequestParam String symbol) {
        blacklistService.clearSymbol(symbol);
        return ResponseEntity.ok(Map.of("status", "ok", "symbol", symbol));
    }
}
