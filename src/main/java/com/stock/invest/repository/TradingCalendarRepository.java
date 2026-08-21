package com.stock.invest.repository;

import com.stock.invest.entity.TradingCalendarEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 交易日历数据访问层。
 * 提供按市场+日期查询、按年查询、upsert 操作。
 */
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
     * Upsert：存在则更新，不存在则创建（P2-2）。
     * <p>原 MySQL 专有 {@code INSERT ... ON DUPLICATE KEY UPDATE ... VALUES()} 语法
     * 在 MySQL 8.0.20+ 已弃用且 H2 测试环境不兼容，改为跨库的"查-改-插"。</p>
     * <p>并发唯一键冲突（{@link org.springframework.dao.DataIntegrityViolationException}）
     * 不在本方法内捕获重试：插入冲突会把当前事务标记为 rollback-only，同一事务内重试
     * 看似成功但提交时抛 UnexpectedRollbackException（评审 D-2）。冲突重试由
     * {@code TradingCalendarDbService} 放到 REQUIRES_NEW 全新事务中完成（不继承
     * 外层事务的 rollback-only 标记）。</p>
     */
    default int upsert(String market, LocalDate tradeDate, Boolean isOpen,
                       String source, String type, String detail) {
        Optional<TradingCalendarEntity> existing = findByMarketAndTradeDate(market, tradeDate);
        if (existing.isPresent()) {
            TradingCalendarEntity entity = existing.get();
            entity.setIsOpen(isOpen);
            entity.setSource(source);
            entity.setType(type);
            entity.setDetail(detail);
            save(entity);
            return 1;
        }
        TradingCalendarEntity entity = new TradingCalendarEntity();
        entity.setMarket(market);
        entity.setTradeDate(tradeDate);
        entity.setIsOpen(isOpen);
        entity.setSource(source);
        entity.setType(type);
        entity.setDetail(detail);
        save(entity);
        return 1;
    }
}
