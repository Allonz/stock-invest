package com.stock.invest.repository;

import com.stock.invest.entity.StockDailyBar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StockDailyBarRepository extends JpaRepository<StockDailyBar, Long> {

    List<StockDailyBar> findTop7BySymbolOrderByTradeDateDesc(String symbol);

    List<StockDailyBar> findTop7BySymbolAndSourceOrderByTradeDateDesc(String symbol, String source);

    List<StockDailyBar> findBySymbolOrderByTradeDateDesc(String symbol, Pageable pageable);

    List<StockDailyBar> findBySymbolAndSourceOrderByTradeDateDesc(String symbol, String source, Pageable pageable);

    Optional<StockDailyBar> findBySymbolAndTradeDate(String symbol, LocalDate tradeDate);

    /**
     * 批量查询 - 优化 N+1 问题，使用 IN 子句一次性查询多个 symbol 的数据
     */
    @Query("SELECT b FROM StockDailyBar b WHERE b.symbol IN :symbols AND b.source = :src "
            + "AND b.tradeDate = :td ORDER BY b.symbol ASC, b.tradeDate ASC")
    List<StockDailyBar> findBySymbolsInAndSourceAndTradeDate(
            @Param("symbols") Collection<String> symbols,
            @Param("src") String source,
            @Param("td") LocalDate tradeDate);

    @Query("SELECT DISTINCT b.symbol FROM StockDailyBar b WHERE b.tradeDate = :td AND b.source = :src "
            + "AND b.closePrice >= :minP AND b.closePrice <= :maxP")
    List<String> findDistinctSymbolsByTradeDateAndSourceAndClosePriceBetween(
            @Param("td") LocalDate tradeDate,
            @Param("src") String source,
            @Param("minP") double minPrice,
            @Param("maxP") double maxPrice);

    @Query("SELECT DISTINCT b.symbol FROM StockDailyBar b WHERE b.tradeDate = :td "
            + "AND b.closePrice >= :minP AND b.closePrice <= :maxP")
    List<String> findDistinctSymbolsByTradeDateAndClosePriceBetween(
            @Param("td") LocalDate tradeDate,
            @Param("minP") double minPrice,
            @Param("maxP") double maxPrice);

    @Query("SELECT DISTINCT b.tradeDate FROM StockDailyBar b WHERE b.source = :src ORDER BY b.tradeDate ASC")
    List<LocalDate> findDistinctTradeDatesBySourceAsc(@Param("src") String src);

    @Query("SELECT b FROM StockDailyBar b WHERE b.symbol IN :symbols AND b.source = :src "
            + "ORDER BY b.symbol ASC, b.tradeDate DESC")
    List<StockDailyBar> findBySymbolInAndSourceOrderBySymbolAscTradeDateDesc(
            @Param("symbols") Collection<String> symbols,
            @Param("src") String source);

    @Query("SELECT b FROM StockDailyBar b WHERE b.source = :src AND b.tradeDate IN :dates ORDER BY b.symbol ASC, b.tradeDate ASC")
    List<StockDailyBar> findBySourceAndTradeDateInOrderBySymbolAscTradeDateAsc(
            @Param("src") String src,
            @Param("dates") Collection<LocalDate> dates);

    @Query("SELECT DISTINCT b.symbol FROM StockDailyBar b ORDER BY b.symbol ASC")
    List<String> findAllSymbols();

    @Query("SELECT COUNT(DISTINCT b.symbol) FROM StockDailyBar b")
    long countDistinctSymbols();

    @Query("SELECT DISTINCT b.source FROM StockDailyBar b ORDER BY b.source ASC")
    List<String> findAllSources();

    @Query("SELECT b FROM StockDailyBar b WHERE b.tradeDate BETWEEN :startDate AND :endDate ORDER BY b.tradeDate DESC")
    List<StockDailyBar> findByTradeDateBetweenOrderByTradeDateDesc(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT b FROM StockDailyBar b WHERE b.source = :source AND b.tradeDate BETWEEN :startDate AND :endDate ORDER BY b.tradeDate DESC")
    List<StockDailyBar> findBySourceAndTradeDateBetween(
            @Param("source") String source,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT b FROM StockDailyBar b WHERE b.source = :source ORDER BY b.tradeDate DESC")
    List<StockDailyBar> findBySourceOrderByTradeDateDesc(@Param("source") String source);

    /**
     * 根据 symbol 列表查询有 name 的记录（取每个 symbol 最新的一条）
     */
    @Query("SELECT b FROM StockDailyBar b WHERE b.symbol IN :symbols AND b.name IS NOT NULL ORDER BY b.tradeDate DESC")
    List<StockDailyBar> findBySymbolInAndNameIsNotNull(@Param("symbols") List<String> symbols);

    /**
     * 多条件筛选分页查询（所有条件均为可选）
     */
    @Query("SELECT b FROM StockDailyBar b WHERE "
            + "(:symbol IS NULL OR b.symbol = :symbol) AND "
            + "(:tradeDate IS NULL OR b.tradeDate = :tradeDate) AND "
            + "(:source IS NULL OR b.source = :source)")
    Page<StockDailyBar> findFiltered(
            @Param("symbol") String symbol,
            @Param("tradeDate") LocalDate tradeDate,
            @Param("source") String source,
            Pageable pageable);
}