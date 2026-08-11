package com.supplymind.day5.foundation;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test-only AT-XR-001/002 fixture-side oracle.  It records frozen merge, de-duplication, ordering,
 * and fault-reporting expectations without certifying an unimplemented D5-T02 query service.
 */
class Day5CrossYearHistoryContractHarnessTest {

    @Test
    void decemberToJanuaryMultiFileMergeReturnsFourUniquePublishedRecordsInBusinessTimeOrder() {
        List<HistoryPartition> fixtures = List.of(
                available("2025-12", row("usd|2025-12-20", "2025-12-20", "6.9000"),
                        row("usd|2025-12-26", "2025-12-26", "6.9100")),
                available("2026-01", row("usd|2026-01-02", "2026-01-02", "6.9200"),
                        row("usd|2026-01-10", "2026-01-10", "6.9300"),
                        row("usd|2026-01-02", "2026-01-02", "6.9200")));

        QueryOutcome outcome = ReferenceHistoryQuery.merge(fixtures,
                LocalDate.of(2025, 12, 20), LocalDate.of(2026, 1, 10));

        assertEquals(List.of("usd|2025-12-20", "usd|2025-12-26", "usd|2026-01-02", "usd|2026-01-10"),
                outcome.rows().stream().map(HistoryRow::businessKey).toList());
        assertTrue(outcome.errors().isEmpty());
        assertTrue(outcome.missingPartitions().isEmpty());
    }

    @Test
    void conflictingDuplicatesMissingCorruptAndManifestMismatchAreExplicitInsteadOfSilentlyDropped() {
        QueryOutcome outcome = ReferenceHistoryQuery.merge(List.of(
                        available("2025-12", row("usd|2025-12-20", "2025-12-20", "6.9000")),
                        available("2026-01", row("usd|2025-12-20", "2025-12-20", "7.9000")),
                        missing("2026-02"), corrupt("2026-03"), manifestMismatch("2026-04")),
                LocalDate.of(2025, 12, 20), LocalDate.of(2026, 4, 30));

        assertEquals(List.of("2026-02"), outcome.missingPartitions());
        assertEquals(List.of("CONFLICTING_DUPLICATE:usd|2025-12-20", "CORRUPT:2026-03", "MANIFEST_MISMATCH:2026-04"),
                outcome.errors());
        assertEquals(1, outcome.rows().size(), "only the original, unambiguous published row can participate");
    }

    @Test
    void reverseDateRangeFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> ReferenceHistoryQuery.merge(List.of(), LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 1)));
    }

    private static HistoryRow row(String businessKey, String businessDate, String value) {
        return new HistoryRow(businessKey, LocalDate.parse(businessDate), value);
    }

    private static HistoryPartition available(String partition, HistoryRow... rows) {
        return new HistoryPartition(partition, State.AVAILABLE, List.of(rows));
    }

    private static HistoryPartition missing(String partition) {
        return new HistoryPartition(partition, State.MISSING, List.of());
    }

    private static HistoryPartition corrupt(String partition) {
        return new HistoryPartition(partition, State.CORRUPT, List.of());
    }

    private static HistoryPartition manifestMismatch(String partition) {
        return new HistoryPartition(partition, State.MANIFEST_MISMATCH, List.of());
    }

    private enum State { AVAILABLE, MISSING, CORRUPT, MANIFEST_MISMATCH }

    private record HistoryRow(String businessKey, LocalDate businessDate, String canonicalPayload) {
    }

    private record HistoryPartition(String partition, State state, List<HistoryRow> rows) {
    }

    private record QueryOutcome(List<HistoryRow> rows, List<String> missingPartitions, List<String> errors) {
    }

    private static final class ReferenceHistoryQuery {
        private static QueryOutcome merge(List<HistoryPartition> partitions, LocalDate from, LocalDate to) {
            if (from.isAfter(to)) {
                throw new IllegalArgumentException("reverse date range");
            }
            Map<String, HistoryRow> unique = new LinkedHashMap<>();
            List<String> missing = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            for (HistoryPartition partition : partitions) {
                switch (partition.state()) {
                    case MISSING -> missing.add(partition.partition());
                    case CORRUPT -> errors.add("CORRUPT:" + partition.partition());
                    case MANIFEST_MISMATCH -> errors.add("MANIFEST_MISMATCH:" + partition.partition());
                    case AVAILABLE -> partition.rows().stream()
                            .filter(row -> !row.businessDate().isBefore(from) && !row.businessDate().isAfter(to))
                            .forEach(row -> mergeRow(unique, errors, row));
                }
            }
            return new QueryOutcome(unique.values().stream()
                    .sorted(Comparator.comparing(HistoryRow::businessDate).thenComparing(HistoryRow::businessKey)).toList(),
                    List.copyOf(missing), List.copyOf(errors));
        }

        private static void mergeRow(Map<String, HistoryRow> unique, List<String> errors, HistoryRow incoming) {
            HistoryRow existing = unique.putIfAbsent(incoming.businessKey(), incoming);
            if (existing != null && !existing.canonicalPayload().equals(incoming.canonicalPayload())) {
                errors.add("CONFLICTING_DUPLICATE:" + incoming.businessKey());
            }
        }
    }
}
