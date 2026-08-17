package com.stock.invest.repository;

import com.stock.invest.entity.StockDailyBar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StockDailyBarRepository extends JpaRepository<StockDailyBar, Long> {

    Optional<StockDailyBar> findBySymbolAndTradeDate(String symbol, LocalDate tradeDate);

    List<StockDailyBar> findBySymbolOrderByTradeDateDesc(String symbol, Pageable pageable);

    /**
     * 查某股票前一个交易日的数据（用于 changePercent 自动计算）
     */
    Optional<StockDailyBar> findTopBySymbolAndTradeDateBeforeOrderByTradeDateDesc(
            String symbol, LocalDate tradeDate);

    /**
     * 批量查询 - 优化 N+1 问题，使用 IN 子句一次性查询多个 symbol 的数据
     */
    @Query("SELECT b FROM StockDailyBar b WHERE b.symbol IN :symbols AND b.source = :src "
            + "AND b.tradeDate = :td ORDER BY b.symbol ASC, b.tradeDate ASC")
    List<StockDailyBar> findBySymbolsInAndSourceAndTradeDate(
            @Param("symbols") Collection<String> symbols,
            @Param("src") String source,
            @Param("td") LocalDate tradeDate);
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
    /**
     * 根据 symbol 列表查询有 name 的记录（取每个 symbol 最新的一条，P2-7）。
     * <p>原实现返回全部匹配行（注释与实现不符，靠调用方 toMap 合并键兜底），
     * 改为子查询精确取每个 symbol 的 tradeDate 最大行。</p>
     */
    @Query("SELECT b FROM StockDailyBar b WHERE b.symbol IN :symbols AND b.name IS NOT NULL "
            + "AND b.tradeDate = (SELECT MAX(b2.tradeDate) FROM StockDailyBar b2 "
            + "WHERE b2.symbol = b.symbol AND b2.name IS NOT NULL) ORDER BY b.tradeDate DESC")
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

    // ---- 字段增补（2026-08-14）----

    /** 发现阶段：从未检查过的记录（存量回填标记用，分批处理） */
    @Query("SELECT b FROM StockDailyBar b WHERE b.fieldFillStatus IS NULL")
    List<StockDailyBar> findUnchecked(Pageable pageable);

    /** 增补阶段：待增补记录（最新日期优先，保证当日/近期待补先处理，DB 侧限量） */
    @Query("SELECT b FROM StockDailyBar b WHERE b.fieldFillStatus = :status ORDER BY b.tradeDate DESC")
    List<StockDailyBar> findByFieldFillStatus(@Param("status") String status, Pageable pageable);

    /** 超窗 PENDING 批量确认终态（30 交易日窗口外不补，用户 2026-08-14） */
    @Modifying
    @Query("UPDATE StockDailyBar b SET b.missingFields = NULL, b.fieldFillStatus = 'CONFIRMED' "
            + "WHERE b.fieldFillStatus = 'PENDING' AND b.tradeDate < :minDate")
    int confirmStalePending(@Param("minDate") LocalDate minDate);
}