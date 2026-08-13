package com.supplymind.history;

import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AggregateRecordV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.StorageException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * D5-T02 cross-file, cross-year history query. Reads multiple monthly daily files and yearly
 * aggregate files under the frozen processed paths, merging, deduplicating on a stable
 * business key, sorting deterministically and reporting missing and corrupt files explicitly
 * - a missing file is never zero, a corrupt file is never silently treated as no data, and
 * identical duplicates collapse while conflicting duplicates are reported instead of being
 * silently resolved by read order. All parsing reuses CsvV1Codec and ManifestVerifier.
 */
public final class HistoryQueryService {

    private final DataRoot dataRoot;

    public HistoryQueryService(DataRoot dataRoot) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
    }

    public DailyHistoryResult queryDaily(String itemId, LocalDate from, LocalDate to) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isAfter(to)) {
            throw new StorageException("history query from must not be after to");
        }
        List<String> missingRefs = new ArrayList<>();
        List<String> corruptRefs = new ArrayList<>();
        List<DailyRecordV1> rows = new ArrayList<>();
        YearMonth cursor = YearMonth.from(from);
        YearMonth last = YearMonth.from(to);
        while (!cursor.isAfter(last)) {
            String dailyRef = DataPaths.dailyRef(itemId, cursor);
            Path path = dataRoot.resolveDataRef(dailyRef);
            Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(dailyRef));
            if (!Files.isRegularFile(path)) {
                missingRefs.add(dailyRef);
                cursor = cursor.plusMonths(1);
                continue;
            }
            if (!ManifestVerifier.matches(dataRoot, dailyRef, path, manifestPath)) {
                corruptRefs.add(dailyRef);
                cursor = cursor.plusMonths(1);
                continue;
            }
            try {
                for (DailyRecordV1 row : CsvV1Codec.decodeDaily(Files.readAllBytes(path))) {
                    LocalDate businessDate = LocalDate.parse(row.businessDate());
                    if (!businessDate.isBefore(from) && !businessDate.isAfter(to)) {
                        rows.add(row);
                    }
                }
            } catch (RuntimeException | IOException exception) {
                corruptRefs.add(dailyRef + " (" + exception.getMessage() + ")");
            }
            cursor = cursor.plusMonths(1);
        }
        MergedDaily merged = mergeDaily(rows);
        return new DailyHistoryResult(merged.rows(), missingRefs, corruptRefs, merged.conflicts());
    }

    public AggregateHistoryResult queryAggregate(
            String itemId, String grain, int fromYear, int toYear
    ) {
        Objects.requireNonNull(itemId, "itemId");
        if (!List.of("month", "quarter", "halfyear", "year").contains(grain)) {
            throw new StorageException("Unsupported aggregate grain: " + grain);
        }
        if (fromYear > toYear) {
            throw new StorageException("history query fromYear must not be after toYear");
        }
        List<String> missingRefs = new ArrayList<>();
        List<String> corruptRefs = new ArrayList<>();
        List<AggregateRecordV1> rows = new ArrayList<>();
        for (int year = fromYear; year <= toYear; year++) {
            String aggregateRef = DataPaths.aggregateRef(itemId, grain, year);
            Path path = dataRoot.resolveDataRef(aggregateRef);
            Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(aggregateRef));
            if (!Files.isRegularFile(path)) {
                missingRefs.add(aggregateRef);
                continue;
            }
            if (!ManifestVerifier.matches(dataRoot, aggregateRef, path, manifestPath)) {
                corruptRefs.add(aggregateRef);
                continue;
            }
            try {
                rows.addAll(CsvV1Codec.decodeAggregate(Files.readAllBytes(path)));
            } catch (RuntimeException | IOException exception) {
                corruptRefs.add(aggregateRef + " (" + exception.getMessage() + ")");
            }
        }
        MergedAggregate merged = mergeAggregate(rows);
        return new AggregateHistoryResult(merged.rows(), missingRefs, corruptRefs, merged.conflicts());
    }

    private MergedDaily mergeDaily(List<DailyRecordV1> rows) {
        Map<DailyKey, DailyRecordV1> unique = new LinkedHashMap<>();
        Set<DailyKey> conflictingKeys = new java.util.HashSet<>();
        for (DailyRecordV1 row : rows) {
            DailyKey key = DailyKey.of(row);
            DailyRecordV1 previous = unique.putIfAbsent(key, row);
            if (previous != null && !previous.equals(row)) {
                conflictingKeys.add(key);
            }
        }
        // F2: a conflicting key must never return an arbitrary record - all rows of the key
        // are excluded from usable results and the key is reported, independent of traversal
        // order. Identical records still deduplicate deterministically (first occurrence only).
        List<String> conflicts = new ArrayList<>();
        Set<DailyKey> emitted = new java.util.HashSet<>();
        List<DailyRecordV1> merged = new ArrayList<>();
        for (DailyRecordV1 row : rows) {
            DailyKey key = DailyKey.of(row);
            if (conflictingKeys.contains(key)) {
                if (!conflicts.contains("daily " + key)) {
                    conflicts.add("daily " + key);
                }
                continue;
            }
            if (emitted.add(key)) {
                merged.add(row);
            }
        }
        merged.sort(DailyRecordV1.ORDER);
        return new MergedDaily(merged, conflicts);
    }

    private MergedAggregate mergeAggregate(List<AggregateRecordV1> rows) {
        Map<AggregateKey, AggregateRecordV1> unique = new LinkedHashMap<>();
        Set<AggregateKey> conflictingKeys = new java.util.HashSet<>();
        for (AggregateRecordV1 row : rows) {
            AggregateKey key = AggregateKey.of(row);
            AggregateRecordV1 previous = unique.putIfAbsent(key, row);
            if (previous != null && !previous.equals(row)) {
                conflictingKeys.add(key);
            }
        }
        List<String> conflicts = new ArrayList<>();
        Set<AggregateKey> emitted = new java.util.HashSet<>();
        List<AggregateRecordV1> merged = new ArrayList<>();
        for (AggregateRecordV1 row : rows) {
            AggregateKey key = AggregateKey.of(row);
            if (conflictingKeys.contains(key)) {
                if (!conflicts.contains("aggregate " + key)) {
                    conflicts.add("aggregate " + key);
                }
                continue;
            }
            if (emitted.add(key)) {
                merged.add(row);
            }
        }
        merged.sort(AggregateRecordV1.ORDER);
        return new MergedAggregate(merged, conflicts);
    }

    private record MergedDaily(List<DailyRecordV1> rows, List<String> conflicts) {
    }

    private record MergedAggregate(List<AggregateRecordV1> rows, List<String> conflicts) {
    }

    /** Stable daily business key: identity/context fields, never read-order dependent. */
    private record DailyKey(
            String businessDate, String itemId, String providerType, String actualSourceName,
            String accessMethod, String validationStatus, String validationVersion,
            String canonicalSpecCode, String calculationVersion, int calculationScale,
            int displayScale, String roundingMode, String calendarVersion, String currency, String unit
    ) {
        static DailyKey of(DailyRecordV1 row) {
            return new DailyKey(
                    row.businessDate(), row.itemId(), row.providerType().wireValue(),
                    row.actualSourceName(), row.accessMethod().wireValue(),
                    row.validationStatus().wireValue(), row.validationVersion(),
                    row.canonicalSpecCode(), row.calculationVersion(), row.calculationScale(),
                    row.displayScale(), row.roundingMode().name(), row.calendarVersion(),
                    row.currency(), row.unit());
        }

        @Override
        public String toString() {
            return businessDate + "/" + itemId + "/" + providerType + "/" + actualSourceName
                    + "/" + validationStatus + "/" + validationVersion + "/" + canonicalSpecCode;
        }
    }

    /** Stable aggregate business key. */
    private record AggregateKey(
            String grain, String periodStart, String periodEnd, String itemId, String providerType,
            String actualSourceName, String accessMethod, String validationStatus,
            String validationVersion, String canonicalSpecCode, String calculationVersion,
            int calculationScale, int displayScale, String roundingMode, String calendarVersion,
            String currency, String unit
    ) {
        static AggregateKey of(AggregateRecordV1 row) {
            return new AggregateKey(
                    row.grain().wireValue(), row.periodStart(), row.periodEnd(), row.itemId(),
                    row.providerType().wireValue(), row.actualSourceName(),
                    row.accessMethod().wireValue(), row.validationStatus().wireValue(),
                    row.validationVersion(), row.canonicalSpecCode(), row.calculationVersion(),
                    row.calculationScale(), row.displayScale(), row.roundingMode().name(),
                    row.calendarVersion(), row.currency(), row.unit());
        }

        @Override
        public String toString() {
            return grain + "/" + periodStart + "/" + itemId + "/" + providerType + "/"
                    + actualSourceName + "/" + validationVersion + "/" + canonicalSpecCode;
        }
    }

    public record DailyHistoryResult(
            List<DailyRecordV1> rows, List<String> missingRefs, List<String> corruptRefs,
            List<String> conflictKeys
    ) {
    }

    public record AggregateHistoryResult(
            List<AggregateRecordV1> rows, List<String> missingRefs, List<String> corruptRefs,
            List<String> conflictKeys
    ) {
    }
}
