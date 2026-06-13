package com.stock.invest.repository;

import com.stock.invest.entity.StockDataSourcePriority;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockDataSourcePriorityRepository
        extends JpaRepository<StockDataSourcePriority, Long> {

    List<StockDataSourcePriority> findBySymbolOrderByLastSuccessTimeDesc(String symbol);

    Optional<StockDataSourcePriority> findBySymbolAndDataSource(String symbol, String dataSource);

    void deleteBySymbolAndDataSource(String symbol, String dataSource);
}