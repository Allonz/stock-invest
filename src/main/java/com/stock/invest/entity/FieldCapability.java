package com.stock.invest.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * 数据源字段能力矩阵 —— 记录"哪些字段在哪些数据源上支持查询、缺失时是否标记补缺"。
 * <p>
 * 补缺字段增补（2026-08-14）数据驱动核心：persist 落库时按 markable 判断是否标记缺失，
 * 增补时按 query_method 决定获取方式（DAILY_KLINE / AFTER_HOURS_API / CALC）。
 */
@Data
@Entity
@Table(
        name = "stock_data_source_field_capability",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_source_field", columnNames = {"data_source", "field_name"})
        }
)
public class FieldCapability {

    /** 补缺获取方式：日K线 */
    public static final String METHOD_DAILY_KLINE = "DAILY_KLINE";
    /** 补缺获取方式：盘后接口 */
    public static final String METHOD_AFTER_HOURS_API = "AFTER_HOURS_API";
    /** 补缺获取方式：本地计算（如隔日涨跌幅） */
    public static final String METHOD_CALC = "CALC";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "data_source", nullable = false, length = 16)
    private String dataSource;

    @Column(name = "field_name", nullable = false, length = 64)
    private String fieldName;

    /** 该源能否查询到该字段（1=能） */
    @Column(nullable = false)
    private Boolean supported;

    /** 该源该字段缺失时是否标记待补（1=标记） */
    @Column(nullable = false)
    private Boolean markable;

    /** 补缺获取方式：DAILY_KLINE / AFTER_HOURS_API / CALC */
    @Column(name = "query_method", nullable = false, length = 32)
    private String queryMethod;

    @Column(length = 255)
    private String remark;

    @Column(name = "created_at", updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false)
    private Instant updatedAt;
}
