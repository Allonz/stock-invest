package com.stock.invest.repository;

import com.stock.invest.entity.DataFillTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
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

    @Query("SELECT t FROM DataFillTask t WHERE t.status = 'retrying' AND t.createdAt <= :cutoff ORDER BY t.createdAt ASC")
    List<DataFillTask> findRetryableTasks(@Param("cutoff") Instant cutoff);
}
