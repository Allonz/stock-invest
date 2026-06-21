package com.stock.invest.repository;

import com.stock.invest.entity.SymbolBlacklist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SymbolBlacklistRepository extends JpaRepository<SymbolBlacklist, Long> {

    Optional<SymbolBlacklist> findBySymbol(String symbol);

    List<SymbolBlacklist> findByStatus(String status);

    @Query("SELECT b.symbol FROM SymbolBlacklist b WHERE b.status = 'active' AND b.consecutive404Count >= 3")
    List<String> findBlacklistedSymbolsWithCountGE3();

    void deleteBySymbol(String symbol);
}
