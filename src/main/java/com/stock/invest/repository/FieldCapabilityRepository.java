package com.stock.invest.repository;

import com.stock.invest.entity.FieldCapability;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FieldCapabilityRepository extends JpaRepository<FieldCapability, Long> {

    Optional<FieldCapability> findByDataSourceAndFieldName(String dataSource, String fieldName);

    List<FieldCapability> findByDataSource(String dataSource);

    List<FieldCapability> findByFieldName(String fieldName);
}
