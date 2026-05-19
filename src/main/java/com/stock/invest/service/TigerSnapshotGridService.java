package com.stock.invest.service;

import com.stock.invest.enums.dto.SnapshotGridViewDto;

/**
 * 聚合 tiger_snap 落库数据，供页面矩阵展示。
 */
public interface TigerSnapshotGridService {

    /**
     * @param maxDateColumns 最多展示多少个交易日的列（时间升序，取最近若干列）
     */
    SnapshotGridViewDto buildGrid(int maxDateColumns);
}
