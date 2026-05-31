package com.stock.invest.repository;

import com.stock.invest.entity.DataFillTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DataFillTaskRepository extends JpaRepository<DataFillTask, Long> {

    Optional<DataFillTask> findBySymbolAndTradeDate(String symbol, LocalDate tradeDate);

    List<DataFillTask> findBySymbolOrderByTradeDateAsc(String symbol);

    List<DataFillTask> findByStatusOrderByCreatedAtAsc(String status);

    List<DataFillTask> findByStatusOrderByCreatedAtDesc(String status);

    List<DataFillTask> findAllByOrderByCreatedAtDesc();

    long countByStatus(String status);

    Page<DataFillTask> findByStatus(String status, Pageable pageable);

    @Query("SELECT t FROM DataFillTask t WHERE "
        + "(:symbol IS NULL OR :symbol = '' OR t.symbol LIKE CONCAT('%', :symbol, '%')) "
        + "AND (:tradeDate IS NULL OR t.tradeDate = :tradeDate) "
        + "AND (:status IS NULL OR :status = '' OR t.status = :status)")
    Page<DataFillTask> findByFilters(
            @Param("symbol") String symbol,
            @Param("tradeDate") LocalDate tradeDate,
            @Param("status") String status,
            Pageable pageable);

    @Query("SELECT t FROM DataFillTask t WHERE t.status = 'retrying' ORDER BY t.createdAt ASC")
    List<DataFillTask> findRetryableTasks();
}
