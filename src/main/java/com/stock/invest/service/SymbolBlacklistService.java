package com.stock.invest.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.entity.SymbolBlacklist;
import com.stock.invest.repository.SymbolBlacklistRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SymbolBlacklistService {

    @Autowired
    private SymbolBlacklistRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 获取需要跳过的黑名单 symbol 列表。
     * 条件：status = 'active' AND consecutive_404_count >= 3
     */
    public List<String> getBlacklistedSymbols() {
        return repository.findBlacklistedSymbolsWithCountGE3();
    }

    public boolean isBlacklisted(String symbol) {
        return repository.findBySymbol(symbol)
                .filter(r -> "active".equals(r.getStatus()))
                .isPresent();
    }

    /**
     * 记录一次"不存在"判定。
     * 如果该 symbol 首次入黑，创建记录；否则更新计数和日期。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordNotFound(String symbol, Map<String, String> sourceErrors) {
        Optional<SymbolBlacklist> existing = repository.findBySymbol(symbol);
        LocalDate today = LocalDate.now();

        if (existing.isPresent()) {
            SymbolBlacklist record = existing.get();
            record.setConsecutive404Count(record.getConsecutive404Count() + 1);
            record.setLast404Date(today);
            record.setSourceErrors(toJson(sourceErrors));
            record.setStatus("active");
            record.setUpdatedAt(LocalDateTime.now());
            repository.save(record);
        } else {
            SymbolBlacklist record = new SymbolBlacklist();
            record.setSymbol(symbol);
            record.setConsecutive404Count(1);
            record.setFirst404Date(today);
            record.setLast404Date(today);
            record.setSourceErrors(toJson(sourceErrors));
            record.setStatus("active");
            record.setUpdatedAt(LocalDateTime.now());
            repository.save(record);
        }
    }

    /**
     * 补缺成功时重置黑名单计数。
     * 说明该股票是可以查到的，移除黑名单标记。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resetCount(String symbol) {
        Optional<SymbolBlacklist> existing = repository.findBySymbol(symbol);
        existing.ifPresent(record -> {
            record.setConsecutive404Count(0);
            record.setStatus("cleared");
            record.setUpdatedAt(LocalDateTime.now());
            repository.save(record);
        });
    }

    /**
     * 手动从黑名单移除（恢复补缺）。
     */
    @Transactional
    public void clearSymbol(String symbol) {
        Optional<SymbolBlacklist> existing = repository.findBySymbol(symbol);
        existing.ifPresent(record -> {
            record.setStatus("cleared");
            record.setUpdatedAt(LocalDateTime.now());
            repository.save(record);
        });
    }

    private String toJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return map.toString();
        }
    }
}
