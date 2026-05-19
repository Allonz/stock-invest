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
        bar.setOpen(10.0D);
        bar.setHigh(11.0D);
        bar.setLow(9.5D);
        bar.setClose(10.5D);
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

    // ── firstItems tests ───────────────────────────────────────────────

    @Test
    public void firstItemsReturnsNFromNewestFirst() {
        KLineData data = new KLineData();
        List<KLineIterator> items = new ArrayList<>();
        items.add(kBar(3000L, 100));
        items.add(kBar(2000L, 200));
        items.add(kBar(1000L, 300));
        data.setItems(items);

        List<KLineIterator> result = KLineDataUtils.firstItems(data, 2);
        assertEquals(2, result.size());
        assertEquals(3000L, result.get(0).getTime());
        assertEquals(2000L, result.get(1).getTime());
    }

    @Test
    public void firstItemsCountExceedsSize_returnsAll() {
        KLineData data = new KLineData();
        List<KLineIterator> items = new ArrayList<>();
        items.add(kBar(3000L, 100));
        items.add(kBar(2000L, 200));
        data.setItems(items);

        List<KLineIterator> result = KLineDataUtils.firstItems(data, 10);
        assertEquals(2, result.size());
    }

    @Test
    public void firstItemsCountIsZero_returnsEmpty() {
        KLineData data = new KLineData();
        List<KLineIterator> items = new ArrayList<>();
        items.add(kBar(3000L, 100));
        data.setItems(items);

        List<KLineIterator> result = KLineDataUtils.firstItems(data, 0);
        assertTrue(result.isEmpty());
    }

    @Test
    public void firstItemsNullData_returnsEmpty() {
        List<KLineIterator> result = KLineDataUtils.firstItems(null, 3);
        assertTrue(result.isEmpty());
    }

    @Test
    public void firstItemsNullItems_returnsEmpty() {
        KLineData data = new KLineData();
        data.setItems(null);
        List<KLineIterator> result = KLineDataUtils.firstItems(data, 3);
        assertTrue(result.isEmpty());
    }

    @Test
    public void firstItemsEmptyList_returnsEmpty() {
        KLineData data = new KLineData();
        data.setItems(new ArrayList<>());
        List<KLineIterator> result = KLineDataUtils.firstItems(data, 3);
        assertTrue(result.isEmpty());
    }

    @Test
    public void firstItemsDoesNotMutateOriginal() {
        KLineData data = new KLineData();
        List<KLineIterator> items = new ArrayList<>();
        items.add(kBar(3000L, 100));
        items.add(kBar(2000L, 200));
        items.add(kBar(1000L, 300));
        data.setItems(items);

        List<KLineIterator> result = KLineDataUtils.firstItems(data, 2);
        assertEquals(3, data.getItems().size());
        assertEquals(2, result.size());
    }

    // ── Combined: sort then firstItems ─────────────────────────────────

    @Test
    public void sortThenFirstItems_returnsCorrectSubset() {
        KLineData data = new KLineData();
        List<KLineIterator> items = new ArrayList<>();
        items.add(kBar(1000L, 100));
        items.add(kBar(4000L, 200));
        items.add(kBar(2000L, 300));
        items.add(kBar(5000L, 400));
        items.add(kBar(3000L, 500));
        data.setItems(items);

        KLineDataUtils.sortItemsNewestFirst(data);
        List<KLineIterator> result = KLineDataUtils.firstItems(data, 3);
        assertEquals(3, result.size());
        assertEquals(5000L, result.get(0).getTime());
        assertEquals(4000L, result.get(1).getTime());
        assertEquals(3000L, result.get(2).getTime());
    }
}
