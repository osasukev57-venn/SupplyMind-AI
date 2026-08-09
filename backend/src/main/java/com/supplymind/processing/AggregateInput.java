package com.supplymind.processing;

import com.supplymind.foundation.model.DailyRecordV1;

import java.util.Objects;

/** One eligible daily row plus its persisted daily file provenance for aggregation input. */
public record AggregateInput(
        DailyRecordV1 dailyRow,
        String dailyFileRef,
        String dailyFileSha256
) {
    public AggregateInput {
        Objects.requireNonNull(dailyRow, "dailyRow");
        Objects.requireNonNull(dailyFileRef, "dailyFileRef");
        Objects.requireNonNull(dailyFileSha256, "dailyFileSha256");
    }
}
