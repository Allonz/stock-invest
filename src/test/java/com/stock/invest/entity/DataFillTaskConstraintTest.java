package com.stock.invest.entity;

import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DataFillTaskConstraintTest {

    @Test
    void shouldHaveCorrectUniqueConstraintName() {
        Table table = DataFillTask.class.getAnnotation(Table.class);
        assertNotNull(table, "DataFillTask should have @Table annotation");

        UniqueConstraint[] constraints = table.uniqueConstraints();
        assertTrue(constraints.length > 0, "Should have at least one unique constraint");

        boolean found = false;
        for (UniqueConstraint uc : constraints) {
            if ("uk_data_fill_task_symbol_trade_date".equals(uc.name())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Should have unique constraint named 'uk_data_fill_task_symbol_trade_date'");
    }

    @Test
    void shouldHaveCorrectColumnsInUniqueConstraint() {
        Table table = DataFillTask.class.getAnnotation(Table.class);
        assertNotNull(table);

        for (UniqueConstraint uc : table.uniqueConstraints()) {
            if ("uk_data_fill_task_symbol_trade_date".equals(uc.name())) {
                assertArrayEquals(new String[]{"symbol", "tradeDate"}, uc.columnNames(),
                        "Unique constraint should be on symbol and tradeDate columns");
            }
        }
    }
}
