package com.stock.invest.controller;

import com.stock.invest.entity.SymbolBlacklist;
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
     * 获取所有黑名单记录
     */
    @GetMapping("/list")
    public List<SymbolBlacklist> list() {
        return blacklistService.listActiveEntries();
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
