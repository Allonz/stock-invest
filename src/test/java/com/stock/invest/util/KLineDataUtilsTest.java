package com.stock.invest.util;

import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for {@link KLineDataUtils}.
 * Calls the real static methods directly — no mocking.
 */
public class KLineDataUtilsTest {

    private static KLineIterator kBar(long time, long volume) {
        KLineIterator bar = new KLineIterator();
        bar.setSymbol("AAA");
        bar.setTime(time);
        bar.setOpen(java.math.BigDecimal.valueOf(10.0D));
        bar.setHigh(java.math.BigDecimal.valueOf(11.0D));
        bar.setLow(java.math.BigDecimal.valueOf(9.5D));
        bar.setClose(java.math.BigDecimal.valueOf(10.5D));
        bar.setVolume(volume);
        bar.setAmount(1000.0D);
        return bar;
    }

    // ── sortItemsNewestFirst tests ──────────────────────────────────────

    @Test
    public void sortItemsAlreadyNewestFirst_unchanged() {
        KLineData data = new KLineData();
        List<KLineIterator> items = new ArrayList<>();
        items.add(kBar(3000L, 100));
        items.add(kBar(2000L, 200));
        items.add(kBar(1000L, 300));
        data.setItems(items);

        KLineDataUtils.sortItemsNewestFirst(data);
        List<KLineIterator> result = data.getItems();
        assertEquals(3, result.size());
        assertEquals(3000L, result.get(0).getTime());
        assertEquals(2000L, result.get(1).getTime());
        assertEquals(1000L, result.get(2).getTime());
    }

    @Test
    public void sortItemsOldestFirst_shouldReorder() {
        KLineData data = new KLineData();
        List<KLineIterator> items = new ArrayList<>();
        items.add(kBar(1000L, 100));
        items.add(kBar(2000L, 200));
        items.add(kBar(3000L, 300));
        data.setItems(items);

        KLineDataUtils.sortItemsNewestFirst(data);
        List<KLineIterator> result = data.getItems();
        assertEquals(3, result.size());
        assertEquals(3000L, result.get(0).getTime());
        assertEquals(2000L, result.get(1).getTime());
        assertEquals(1000L, result.get(2).getTime());
    }

    @Test
    public void sortItemsRandomOrder_shouldSortCorrectly() {
        KLineData data = new KLineData();
        List<KLineIterator> items = new ArrayList<>();
        items.add(kBar(5000L, 100));
        items.add(kBar(1000L, 200));
        items.add(kBar(3000L, 300));
        items.add(kBar(2000L, 400));
        items.add(kBar(4000L, 500));
        data.setItems(items);

        KLineDataUtils.sortItemsNewestFirst(data);
        List<KLineIterator> result = data.getItems();
        assertEquals(5, result.size());
        for (int i = 1; i < result.size(); i++) {
            assertTrue(result.get(i - 1).getTime() >= result.get(i).getTime(),
                    "Items should be sorted newest-first");
        }
    }

    @Test
    public void sortItemsNullData_noException() {
        assertDoesNotThrow(() -> KLineDataUtils.sortItemsNewestFirst(null));
    }

    @Test
    public void sortItemsEmptyItems_noException() {
        KLineData data = new KLineData();
        data.setItems(new ArrayList<>());
        assertDoesNotThrow(() -> KLineDataUtils.sortItemsNewestFirst(data));
        assertTrue(data.getItems().isEmpty());
    }

    @Test
    public void sortItemsSingleItem_unchanged() {
        KLineData data = new KLineData();
        List<KLineIterator> items = new ArrayList<>();
        items.add(kBar(1000L, 100));
        data.setItems(items);

        KLineDataUtils.sortItemsNewestFirst(data);
        assertEquals(1, data.getItems().size());
        assertEquals(1000L, data.getItems().get(0).getTime());
    }
}
