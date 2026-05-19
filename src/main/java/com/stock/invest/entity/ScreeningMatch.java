package com.stock.invest.entity;

import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 持久化一次低价放量模式筛选的命中记录。
 */
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "screening_match",
        indexes = {
                @Index(name = "idx_screening_match_trade_date_price", columnList = "tradeDate,price"),
                @Index(name = "idx_screening_match_batch_id", columnList = "batchId"),
                @Index(name = "idx_screening_match_window_days", columnList = "windowDays"),
                @Index(name = "idx_screening_match_batch_id_window_days", columnList = "batchId,windowDays")
        }
)
public class ScreeningMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String batchId;

    @Column(nullable = false, length = 32)
    private String dataSource;

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column
    private Double lastClose;

    @Column(nullable = false)
    private LocalDate tradeDate;

    @Column
    private Double price;

    @Column(nullable = false)
    private Boolean rise;

    /** 筛选窗口天数: 2-7 */
    @Column(name = "window_days", nullable = false)
    private Integer windowDays;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
