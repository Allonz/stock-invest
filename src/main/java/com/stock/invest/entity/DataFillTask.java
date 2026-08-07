package com.stock.invest.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "data_fill_task",
        uniqueConstraints = {
                // R2 P3-6：约束名对齐 V1__baseline.sql 实际名（生产库 DDL）
                @UniqueConstraint(name = "uk_data_fill_task_symbol_missing_date", columnNames = {"symbol", "tradeDate"})
        }
)
public class DataFillTask {

    public static final int MAX_RETRIES = 35;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false, length = 32)
    private String status = "pending";

    @Column(nullable = false)
    private Integer retryCount = 0;

    @Column
    private LocalDate retryDate;

    @Column(nullable = false)
    private Integer dayCount = 0;

    @Column(length = 512)
    private String lastError;

    /** P2-4：乐观锁版本号 —— 并发读-改-写（retryCount/dayCount）冲突兜底 */
    @Version
    @Column(nullable = false)
    private Integer version = 0;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    public int getMaxRetries() {
        return MAX_RETRIES;
    }
}
