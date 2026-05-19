package com.stock.invest.util;

import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * K 线序列工具：统一为「最新一根在索引 0」的排序与截取。
 */
public final class KLineDataUtils {

    private KLineDataUtils() {
    }

    /**
     * 按时间降序排列（最新在前）。若时间为 0，则保持相对顺序不变。
     */
    public static void sortItemsNewestFirst(KLineData data) {
        if (data == null || data.getItems() == null || data.getItems().isEmpty()) {
            return;
        }
        List<KLineIterator> sorted = new ArrayList<>(data.getItems());
        sorted.sort(Comparator.comparingLong(KLineIterator::getTime).reversed());
        data.setItems(sorted);
    }

    public static List<KLineIterator> firstItems(KLineData data, int count) {
        if (data == null || data.getItems() == null) {
            return new ArrayList<>();
        }
        List<KLineIterator> items = data.getItems();
        int n = Math.min(count, items.size());
        return new ArrayList<>(items.subList(0, n));
    }
}
