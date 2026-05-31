package com.stock.invest.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 交易日历持久化实体。
 * 记录每个市场每日的开盘/休市状态，从多个数据源抓取后落库。
 * 唯一约束：(market, trade_date)，支持 upsert。
 */
@Entity
@Table(name = "trading_calendar", uniqueConstraints = {
    @UniqueConstraint(name = "uk_market_trade_date", columnNames = {"market", "trade_date"})
})
public class TradingCalendarEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 市场代码，如 "US" */
    @Column(nullable = false, length = 10)
    private String market;

    /** 交易日期（美东日期） */
    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    /** 是否开盘 */
    @Column(name = "is_open", nullable = false)
    private Boolean isOpen;

    /** 数据来源：tiger / tigeropen / alpaca */
    @Column(length = 50)
    private String source;

    /** 类型：TRADING / HOLIDAY / WEEKEND */
    @Column(length = 30)
    private String type;

    /** 详细说明 */
    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // --- getters & setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }

    public LocalDate getTradeDate() { return tradeDate; }
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }

    public Boolean getIsOpen() { return isOpen; }
    public void setIsOpen(Boolean isOpen) { this.isOpen = isOpen; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return "TradingCalendarEntity{" +
                "id=" + id +
                ", market='" + market + '\'' +
                ", tradeDate=" + tradeDate +
                ", isOpen=" + isOpen +
                ", source='" + source + '\'' +
                ", type='" + type + '\'' +
                '}';
    }
}
