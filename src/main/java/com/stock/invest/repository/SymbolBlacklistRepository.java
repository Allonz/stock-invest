package com.stock.invest.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.stock.invest.entity.SymbolBlacklist;

public interface SymbolBlacklistRepository extends JpaRepository<SymbolBlacklist, Long> {

    Optional<SymbolBlacklist> findBySymbol(String symbol);

    List<SymbolBlacklist> findByStatus(String status);

    @Query("SELECT b.symbol FROM SymbolBlacklist b WHERE b.status = 'active' AND b.consecutive404Count >= 3")
    List<String> findBlacklistedSymbolsWithCountGE3();

    void deleteBySymbol(String symbol);
}
