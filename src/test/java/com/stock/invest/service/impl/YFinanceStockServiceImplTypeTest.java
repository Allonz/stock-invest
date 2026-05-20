package com.stock.invest.service.impl;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;

import static org.junit.jupiter.api.Assertions.*;

class YFinanceStockServiceImplTypeTest {

    @Test
    void shouldNotHaveSuppressWarningsUncheckedAnnotation() {
        // Verify the class does NOT have @SuppressWarnings("unchecked")
        SuppressWarnings[] annotations = YFinanceStockServiceImpl.class
                .getAnnotationsByType(SuppressWarnings.class);

        for (SuppressWarnings ann : annotations) {
            for (String value : ann.value()) {
                if ("unchecked".equals(value)) {
                    fail("YFinanceStockServiceImpl should not have @SuppressWarnings(\"unchecked\")");
                }
            }
        }

        // If we get here, no unchecked suppression was found
        assertTrue(true, "No @SuppressWarnings(\"unchecked\") found on the class");
    }
}
