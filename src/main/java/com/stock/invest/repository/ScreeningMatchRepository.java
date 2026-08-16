package com.stock.invest.repository;

import com.stock.invest.entity.ScreeningMatch;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ScreeningMatchRepository extends JpaRepository<ScreeningMatch, Long> {

    List<ScreeningMatch> findByBatchIdOrderByIdAsc(String batchId);

    List<ScreeningMatch> findByBatchIdAndWindowDaysOrderByIdAsc(String batchId, Integer windowDays);

    List<ScreeningMatch> findByTradeDateOrderByPriceDesc(LocalDate tradeDate);

    /** P2-5：查询某交易日全部命中记录（用于 saveAll 前查重） */
    List<ScreeningMatch> findByTradeDate(LocalDate tradeDate);

    Optional<ScreeningMatch> findTopByOrderByTradeDateDescIdDesc();

    /** 按交易日查询最新一次筛选批次的首条记录（id 最大 = 最近一次筛选） */
    Optional<ScreeningMatch> findTopByTradeDateOrderByIdDesc(LocalDate tradeDate);

    @Query("SELECT DISTINCT sm.batchId FROM ScreeningMatch sm ORDER BY sm.batchId DESC")
    List<String> findDistinctBatchIds();

    @Query("SELECT sm.batchId, COUNT(sm) as matchCount, MAX(sm.tradeDate) as lastTradeDate FROM ScreeningMatch sm GROUP BY sm.batchId ORDER BY MAX(sm.tradeDate) DESC")
    List<Object[]> findBatchSummary();

    @Query("SELECT sm.windowDays, COUNT(sm) FROM ScreeningMatch sm WHERE sm.batchId = :batchId GROUP BY sm.windowDays ORDER BY sm.windowDays")
    List<Object[]> countByBatchIdGroupByWindowDays(@Param("batchId") String batchId);

    /**
     * 按 batchId + windowDays + algorithm 查询匹配记录。
     */
    List<ScreeningMatch> findByBatchIdAndWindowDaysAndAlgorithmOrderByIdAsc(
            String batchId, Integer windowDays, String algorithm);
}
