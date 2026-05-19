package com.stock.invest.entity;

import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;

@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "stock_daily_bar",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_stock_daily_bar_symbol_trade_date", columnNames = {"symbol", "tradeDate"})
        },
        indexes = {
                @Index(name = "idx_stock_daily_bar_trade_date", columnList = "tradeDate"),
                @Index(name = "idx_stock_daily_bar_symbol", columnList = "symbol")
        }
)
public class StockDailyBar {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String symbol;

    /** 截图/客户端展示名称（可选） */
    @Column(name = "stock_name", length = 128)
    private String name;

    @Column(nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false)
    private Double openPrice;

    @Column(nullable = false)
    private Double closePrice;

    @Column(nullable = false)
    private Long volume;

    @Column(nullable = false, length = 16)
    private String source;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
