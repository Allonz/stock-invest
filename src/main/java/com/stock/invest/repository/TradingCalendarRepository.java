package com.stock.invest.repository;

import com.stock.invest.entity.TradingCalendarEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 交易日历数据访问层。
 * 提供按市场+日期查询、按年查询、upsert 操作。
 */
@Repository
public interface TradingCalendarRepository extends JpaRepository<TradingCalendarEntity, Long> {

    Optional<TradingCalendarEntity> findByMarketAndTradeDate(String market, LocalDate tradeDate);

    List<TradingCalendarEntity> findByMarketAndTradeDateBetween(
            String market, LocalDate start, LocalDate end);

    /** 按年查询，按 tradeDate 升序 */
    @Query("SELECT t FROM TradingCalendarEntity t WHERE t.market = :market " +
           "AND YEAR(t.tradeDate) = :year ORDER BY t.tradeDate ASC")
    List<TradingCalendarEntity> findByMarketAndYear(
            @Param("market") String market, @Param("year") int year);

    /**
     * Upsert：存在则更新，不存在则创建。
     * 唯一键：(market, trade_date)。
     */
    @Modifying
    @Query(nativeQuery = true, value =
        "INSERT INTO trading_calendar (market, trade_date, is_open, source, type, detail, created_at, updated_at) " +
        "VALUES (:market, :tradeDate, :isOpen, :source, :type, :detail, NOW(), NOW()) " +
        "ON DUPLICATE KEY UPDATE " +
        "    is_open = VALUES(is_open), " +
        "    source = VALUES(source), " +
        "    type = VALUES(type), " +
        "    detail = VALUES(detail), " +
        "    updated_at = NOW()")
    int upsert(
            @Param("market") String market,
            @Param("tradeDate") LocalDate tradeDate,
            @Param("isOpen") Boolean isOpen,
            @Param("source") String source,
            @Param("type") String type,
            @Param("detail") String detail);
}
