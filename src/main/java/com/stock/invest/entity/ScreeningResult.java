package com.stock.invest.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 筛选结果记录，存储一次筛选操作的单个结果条目。
 * 与 ScreeningMatch 不同，此实体用于存储独立筛选查询的结果，
 * 而非批量模式匹配的命中记录。
 */
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "screening_result",
        indexes = {
                @Index(name = "idx_screening_result_batch_id", columnList = "batchId"),
                @Index(name = "idx_screening_result_trade_date", columnList = "tradeDate")
        }
)
public class ScreeningResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String batchId;

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(length = 128)
    private String name;

    @Column(nullable = false)
    private LocalDate tradeDate;

    @Column
    private Double price;

    @Column
    private Double changePercent;

    @Column
    private Long volume;

    @Column(length = 64)
    private String strategy;

    @Column(nullable = false)
    private Integer rank;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
