package com.stock.invest.entity;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
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
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false, length = 32)
    private String symbol;

    /** 截图/客户端展示名称（可选） */
    @Column(name = "stock_name", length = 128)
    private String name;

    @Column(nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false)
    private BigDecimal openPrice;

    @Column(nullable = false)
    private BigDecimal highPrice;   // 新增（最高价）

    @Column(nullable = false)
    private BigDecimal lowPrice;    // 新增（最低价）

    @Column(nullable = false)
    private BigDecimal closePrice;

    @Column(nullable = true)
    private BigDecimal changePercent;           // 新增（涨跌幅 %）

    @Column(nullable = true)
    private BigDecimal afterHours;              // 新增（盘后价）

    @Column(nullable = true)
    private BigDecimal afterHoursChangePercent; // 新增（盘后涨跌幅 %）

    @Column(nullable = false)
    private Long volume;

    @Column(nullable = false, length = 16)
    private String source;

    /** 缺失字段列表（逗号分隔，如 "after_hours,after_hours_change_percent"），NULL/空 = 无缺失（2026-08-14） */
    @Column(name = "missing_fields", length = 255)
    private String missingFields;

    /** 字段增补状态：NULL=未检查 / PENDING=待增补 / CONFIRMED=已确认（2026-08-14） */
    @Column(name = "field_fill_status", length = 20)
    private String fieldFillStatus;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
