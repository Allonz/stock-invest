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

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "symbol_blacklist", uniqueConstraints = {
    @UniqueConstraint(name = "uk_symbol_blacklist_symbol", columnNames = {"symbol"})
})
@EntityListeners(AuditingEntityListener.class)
public class SymbolBlacklist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String symbol;

    @Column(name = "consecutive_404_count", nullable = false)
    private Integer consecutive404Count = 0;

    @Column(name = "first_404_date", nullable = false)
    private LocalDate first404Date;

    @Column(name = "last_404_date")
    private LocalDate last404Date;

    @Column(name = "source_errors", length = 1000)
    private String sourceErrors;

    @Column(nullable = false, length = 20)
    private String status = "active";

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
