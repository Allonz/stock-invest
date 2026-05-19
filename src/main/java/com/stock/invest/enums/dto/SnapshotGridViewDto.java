package com.stock.invest.enums.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record SnapshotGridViewDto(
    List<String> dateHeaders,
    List<SnapshotGridRowDto> rows
) {
    public SnapshotGridViewDto {
        dateHeaders = dateHeaders == null ? new ArrayList<>() : new ArrayList<>(dateHeaders);
        rows = rows == null ? new ArrayList<>() : new ArrayList<>(rows);
    }

    @Override
    public List<String> dateHeaders() {
        return Collections.unmodifiableList(dateHeaders);
    }

    @Override
    public List<SnapshotGridRowDto> rows() {
        return Collections.unmodifiableList(rows);
    }

    public boolean isEmpty() {
        return rows.isEmpty() || dateHeaders.isEmpty();
    }
}
