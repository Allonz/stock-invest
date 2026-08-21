package com.stock.invest.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 交易日历持久化实体。
 * 记录每个市场每日的开盘/休市状态，从多个数据源抓取后落库。
 * 唯一约束：(market, trade_date)，支持 upsert。
 *
 * <p>评审 E-5：审计字段（createdAt/updatedAt）统一为 JPA Auditing 风格
 * （{@link AuditingEntityListener} + {@link Instant}），与其余实体一致，
 * 不再使用 @PrePersist/@PreUpdate + LocalDateTime 的手写方案。</p>
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "trading_calendar", uniqueConstraints = {
    @UniqueConstraint(name = "uk_trading_calendar_market_trade_date", columnNames = {"market", "trade_date"})
})
public class TradingCalendarEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 市场代码，如 "US" */
    @Column(nullable = false, length = 16)
    private String market;

    /** 交易日期（美东日期） */
    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    /** 是否开盘 */
    @Column(name = "is_open", nullable = false)
    private Boolean isOpen;

    /** 数据来源：tiger / tigeropen / alpaca */
    @Column(length = 32)
    private String source;

    /** 类型：TRADING / HOLIDAY / WEEKEND */
    @Column(length = 16)
    private String type;

    /** 详细说明 */
    @Column(length = 256)
    private String detail;

    /** 创建时间（JPA Auditing 自动填充，评审 E-5 统一为 Instant） */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 更新时间（JPA Auditing 自动填充） */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

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
