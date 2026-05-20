package com.stock.invest.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDate;

@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "data_fill_task",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_data_fill_task_symbol_missing_date", columnNames = {"symbol", "tradeDate"})
        }
)
public class DataFillTask {

    public static final int MAX_RETRIES = 35;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
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
