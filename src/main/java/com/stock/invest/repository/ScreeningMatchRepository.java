package com.stock.invest.repository;

import com.stock.invest.entity.ScreeningMatch;
import com.stock.invest.enums.dto.ScreeningMatchProjection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ScreeningMatchRepository extends JpaRepository<ScreeningMatch, Long> {

    List<ScreeningMatch> findByBatchIdOrderByIdAsc(String batchId);

    List<ScreeningMatch> findByBatchIdAndWindowDaysOrderByIdAsc(String batchId, Integer windowDays);

    List<ScreeningMatch> findByTradeDateOrderByPriceDesc(LocalDate tradeDate);

    List<ScreeningMatch> findByTradeDateAndPriceBetweenOrderByPriceDesc(LocalDate tradeDate, Double minPrice, Double maxPrice);

    List<ScreeningMatchProjection> findProjectedByTradeDateAndPriceBetweenOrderByPriceDesc(LocalDate tradeDate, Double minPrice, Double maxPrice);

    Optional<ScreeningMatch> findTopByOrderByTradeDateDescIdDesc();

    @Query("SELECT DISTINCT sm.batchId FROM ScreeningMatch sm ORDER BY sm.batchId DESC")
    List<String> findDistinctBatchIds();

    @Query("SELECT sm.batchId, COUNT(sm) as matchCount, MAX(sm.tradeDate) as lastTradeDate FROM ScreeningMatch sm GROUP BY sm.batchId ORDER BY MAX(sm.tradeDate) DESC")
    List<Object[]> findBatchSummary();

    @Query("SELECT sm.windowDays, COUNT(sm) FROM ScreeningMatch sm WHERE sm.batchId = :batchId GROUP BY sm.windowDays ORDER BY sm.windowDays")
    List<Object[]> countByBatchIdGroupByWindowDays(@Param("batchId") String batchId);

    /**
     * 按 batchId + algorithm 查询，返回各窗口命中数。
     */
    @Query("SELECT sm.windowDays, COUNT(sm) FROM ScreeningMatch sm "
         + "WHERE sm.batchId = :batchId AND sm.algorithm = :algorithm "
         + "GROUP BY sm.windowDays ORDER BY sm.windowDays")
    List<Object[]> countByBatchIdAndAlgorithmGroupByWindowDays(
            @Param("batchId") String batchId,
            @Param("algorithm") String algorithm);

    /**
     * 按 batchId + windowDays + algorithm 查询匹配记录。
     */
    List<ScreeningMatch> findByBatchIdAndWindowDaysAndAlgorithmOrderByIdAsc(
            String batchId, Integer windowDays, String algorithm);
}
