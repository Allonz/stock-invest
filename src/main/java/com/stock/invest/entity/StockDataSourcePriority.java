package com.stock.invest.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.time.Instant;

@Data
@Entity
@EntityListeners(org.springframework.data.jpa.domain.support.AuditingEntityListener.class)
@Table(
        name = "stock_data_source_priority",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_sds_priority_symbol_ds", columnNames = {"symbol", "data_source"})
        }
)
public class StockDataSourcePriority {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "data_source", nullable = false, length = 50)
    private String dataSource;

    @Column(name = "last_success_time", nullable = false)
    private LocalDateTime lastSuccessTime;

    @Column(nullable = false, updatable = false)
    @org.springframework.data.annotation.CreatedDate
    private Instant createdAt;

    @Column(nullable = false)
    @org.springframework.data.annotation.LastModifiedDate
    private Instant updatedAt;

    public static StockDataSourcePriority of(String symbol, String dataSource, LocalDateTime successTime) {
        StockDataSourcePriority p = new StockDataSourcePriority();
        p.symbol = symbol;
        p.dataSource = dataSource;
        p.lastSuccessTime = successTime;
        return p;
    }
}