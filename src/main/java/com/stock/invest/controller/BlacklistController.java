package com.stock.invest.controller;

import com.stock.invest.entity.SymbolBlacklist;
import com.stock.invest.repository.SymbolBlacklistRepository;
import com.stock.invest.service.SymbolBlacklistService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/blacklist")
public class BlacklistController {

    private final SymbolBlacklistService symbolBlacklistService;
    private final SymbolBlacklistRepository repository;

    public BlacklistController(SymbolBlacklistService symbolBlacklistService,
                               SymbolBlacklistRepository repository) {
        this.symbolBlacklistService = symbolBlacklistService;
        this.repository = repository;
    }

    /**
     * 获取所有黑名单记录
     */
    @GetMapping("/list")
    public List<SymbolBlacklist> list() {
        return repository.findByStatus("active");
    }

    /**
     * 手动清理一条黑名单记录（解除黑名单）
     */
    @PostMapping("/clear")
    public ResponseEntity<?> clear(@RequestParam String symbol) {
        symbolBlacklistService.clearSymbol(symbol);
        return ResponseEntity.ok(Map.of("status", "ok", "symbol", symbol));
    }
}
