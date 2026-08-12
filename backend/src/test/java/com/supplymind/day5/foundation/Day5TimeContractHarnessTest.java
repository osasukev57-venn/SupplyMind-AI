package com.supplymind.day5.foundation;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test-only AT-TIME-001/002 contract oracle.  It deliberately does not call a Day-5 rotation
 * service: D5-T01 is not implemented on this branch, so these passing tests are harness evidence,
 * not an AT-TIME product PASS.
 */
class Day5TimeContractHarnessTest {

    @Test
    void controlledClockForwardBoundariesCreateOnlyTheBusinessPeriodThatReceivesARecord() {
        ReferenceRotationLedger ledger = new ReferenceRotationLedger();

        write(ledger, "k-jan", LocalDate.of(2026, 1, 31), LocalDate.of(2026, 1, 31));
        write(ledger, "k-feb", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 1));
        write(ledger, "k-mar", LocalDate.of(2026, 3, 31), LocalDate.of(2026, 3, 31));
        write(ledger, "k-apr", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 1));
        write(ledger, "k-jun", LocalDate.of(2026, 6, 30), LocalDate.of(2026, 6, 30));
        write(ledger, "k-jul", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1));
        write(ledger, "k-dec", LocalDate.of(2026, 12, 31), LocalDate.of(2026, 12, 31));
        write(ledger, "k-jan-next", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 1));
        write(ledger, "k-leap-before", LocalDate.of(2028, 2, 28), LocalDate.of(2028, 2, 28));
        write(ledger, "k-leap", LocalDate.of(2028, 2, 29), LocalDate.of(2028, 2, 29));
        write(ledger, "k-leap-after", LocalDate.of(2028, 3, 1), LocalDate.of(2028, 3, 1));

        assertEquals(Set.of(YearMonth.of(2026, 1), YearMonth.of(2026, 2), YearMonth.of(2026, 3),
                        YearMonth.of(2026, 4), YearMonth.of(2026, 6), YearMonth.of(2026, 7),
                        YearMonth.of(2026, 12), YearMonth.of(2027, 1), YearMonth.of(2028, 2),
                        YearMonth.of(2028, 3)), ledger.partitions());
        assertFalse(ledger.partitions().contains(YearMonth.of(2026, 5)),
                "a forward clock must not invent a future or missing-period business file");
        assertEquals(11, ledger.records().size());
    }

    @Test
    void rollbackAndRestartRetainNewerFilesWithoutDuplicateBusinessPublication() {
        ReferenceRotationLedger beforeRollback = new ReferenceRotationLedger();
        write(beforeRollback, "year-rollover", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 1));

        ReferenceRotationLedger restarted = beforeRollback.restart();
        assertFalse(restarted.write("year-rollover", LocalDate.of(2026, 12, 31),
                        LocalDate.of(2027, 1, 1)),
                "a rollback replay of the same business key must remain idempotent");
        assertTrue(restarted.write("old-period-new-key", LocalDate.of(2026, 12, 31),
                        LocalDate.of(2026, 12, 31)));

        assertEquals(Set.of(YearMonth.of(2026, 12), YearMonth.of(2027, 1)), restarted.partitions());
        assertEquals(List.of("old-period-new-key", "year-rollover"),
                restarted.records().stream().map(Record::businessKey).toList(),
                "cross-period reads must be ordered by business date rather than write order");
    }

    @Test
    void aRecordAheadOfTheControlledClockIsRejectedInsteadOfCreatingSyntheticFutureData() {
        ReferenceRotationLedger ledger = new ReferenceRotationLedger();
        assertThrows(IllegalArgumentException.class,
                () -> ledger.write("future", LocalDate.of(2026, 7, 31), LocalDate.of(2026, 8, 1)));
        assertTrue(ledger.records().isEmpty());
    }

    @Disabled("PENDING_IMPLEMENTATION: AT-TIME-003/004 require a dedicated Windows/VM physical system-time run and final Electron package.")
    @Test
    void physicalWindowsClockForwardAndRollbackRemainPendingImplementation() {
        // Intentionally disabled: this fast-R0 lane must not claim physical-system-time coverage.
    }

    private static void write(ReferenceRotationLedger ledger, String key, LocalDate clockDate, LocalDate businessDate) {
        assertTrue(ledger.write(key, clockDate, businessDate));
    }

    private record Record(String businessKey, LocalDate businessDate) {
    }

    private static final class ReferenceRotationLedger {
        private final Set<String> publishedKeys;
        private final List<Record> records;
        private final Set<YearMonth> partitions;

        private ReferenceRotationLedger() {
            this(new LinkedHashSet<>(), new ArrayList<>(), new LinkedHashSet<>());
        }

        private ReferenceRotationLedger(Set<String> publishedKeys, List<Record> records, Set<YearMonth> partitions) {
            this.publishedKeys = publishedKeys;
            this.records = records;
            this.partitions = partitions;
        }

        private boolean write(String businessKey, LocalDate clockDate, LocalDate businessDate) {
            if (publishedKeys.contains(businessKey)) {
                return false;
            }
            if (businessDate.isAfter(clockDate)) {
                throw new IllegalArgumentException("future business data must not be invented");
            }
            publishedKeys.add(businessKey);
            records.add(new Record(businessKey, businessDate));
            partitions.add(YearMonth.from(businessDate));
            return true;
        }

        private ReferenceRotationLedger restart() {
            return new ReferenceRotationLedger(new LinkedHashSet<>(publishedKeys), new ArrayList<>(records),
                    new LinkedHashSet<>(partitions));
        }

        private Set<YearMonth> partitions() {
            return Set.copyOf(partitions);
        }

        private List<Record> records() {
            return records.stream().sorted(Comparator.comparing(Record::businessDate).thenComparing(Record::businessKey)).toList();
        }
    }
}
