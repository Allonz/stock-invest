package com.stock.invest.repository;

import com.stock.invest.entity.DataFillTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DataFillTaskRepository extends JpaRepository<DataFillTask, Long> {

    Optional<DataFillTask> findBySymbolAndTradeDate(String symbol, LocalDate tradeDate);

    List<DataFillTask> findBySymbolOrderByTradeDateAsc(String symbol);

    List<DataFillTask> findByStatusOrderByCreatedAtAsc(String status);

    List<DataFillTask> findByStatusOrderByCreatedAtDesc(String status);

    List<DataFillTask> findAllByOrderByCreatedAtDesc();

    long countByStatus(String status);

    Page<DataFillTask> findByStatus(String status, Pageable pageable);

    /**
     * 多条件筛选分页查询（所有条件均为可选）。
     * <p>P2-7：symbol 由前导通配 LIKE（'%...%'，索引失效）改为前缀匹配
     * （'x%'，可命中 idx_data_fill_tasks_symbol）；语义从"包含"变为"前缀"，
     * 股票代码按前缀搜索符合常规用法。</p>
     */
    @Query("SELECT t FROM DataFillTask t WHERE "
        + "(:symbol IS NULL OR :symbol = '' OR t.symbol LIKE CONCAT(:symbol, '%')) "
        + "AND (:tradeDate IS NULL OR t.tradeDate = :tradeDate) "
        + "AND (:status IS NULL OR :status = '' OR t.status = :status)")
    Page<DataFillTask> findByFilters(
            @Param("symbol") String symbol,
            @Param("tradeDate") LocalDate tradeDate,
            @Param("status") String status,
            Pageable pageable);

    @Query("SELECT t FROM DataFillTask t WHERE t.status = 'retrying' ORDER BY t.createdAt ASC")
    List<DataFillTask> findRetryableTasks();

    @Modifying
    @Query("UPDATE DataFillTask t SET t.status = :newStatus, t.lastError = :error WHERE t.symbol = :symbol AND t.status IN :statuses")
    int updateStatusBySymbolAndStatusIn(
        @Param("symbol") String symbol,
        @Param("statuses") List<String> statuses,
        @Param("newStatus") String newStatus,
        @Param("error") String error
    );

    /**
     * R2 P2-1：仅当 retryDate 非 today 时重置日计数（原子条件更新，替代读-改-写，无乐观锁冲突）。
     * 返回受影响行数（0 = 无需重置）。
     */
    @Modifying
    @Query("UPDATE DataFillTask t SET t.dayCount = 0, t.retryDate = :today "
            + "WHERE t.id = :id AND (t.retryDate IS NULL OR t.retryDate <> :today)")
    int resetDailyCounterIfDateChanged(@Param("id") Long id, @Param("today") LocalDate today);

    /**
     * R2 P2-1：重试计数原子自增（retryCount/dayCount +1，终态字段一并更新，版本无关）。
     * 供 createRetryTask 与 processRetryingTasks 失败分支使用，杜绝乐观锁冲突丢更新。
     */
    @Modifying
    @Query("UPDATE DataFillTask t SET t.retryCount = t.retryCount + 1, t.dayCount = t.dayCount + 1, "
            + "t.status = :status, t.lastError = :error WHERE t.id = :id")
    int incrementRetryCounters(
        @Param("id") Long id,
        @Param("status") String status,
        @Param("error") String error
    );
}
