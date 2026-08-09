package com.supplymind.processing;

import com.supplymind.foundation.model.DailyRecordV1;

import java.util.List;

/** Result of one daily processing run; dailyRef is null when no eligible input produced a row. */
public record DailyResult(String dailyRef, List<DailyRecordV1> rows) {
    public DailyResult {
        rows = List.copyOf(rows);
    }
}
