package com.stock.invest.constant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WindowConstantsTest {

    @Test
    void shouldHaveCorrectConstants() {
        assertEquals(2, WindowConstants.MIN_WINDOW_DAYS);
        assertEquals(7, WindowConstants.MAX_WINDOW_DAYS);
        assertEquals(List.of(2, 3, 4, 5, 6, 7), WindowConstants.ALL_WINDOW_DAYS);
    }
}
