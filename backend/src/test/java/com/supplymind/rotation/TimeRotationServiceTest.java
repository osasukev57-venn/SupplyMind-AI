package com.supplymind.rotation;

import com.supplymind.foundation.model.TimeStateV1;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.TimeStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D5-T01 rotation acceptance (AT-TIME-001/002 backend part): month-end, quarter-end,
 * June half-year boundary, year-end, leap day, forward jump, rollback and restart all roll
 * the recoverable time state correctly while never touching business files. Physical Windows
 * time changes belong to AT-TIME-003/004 (D10-T02) and are intentionally absent here.
 */
class TimeRotationServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void monthEndRollsPeriodAndAdvanceStateMonotonically() {
        Harness harness = harness();
        assertTrue(harness.rotation().check(at("2026-08-31T23:00:00+08:00")).firstRun());
        TimeRotationService.RotationCheckResult next = harness.rotation().check(at("2026-09-01T00:30:00+08:00"));
        assertTrue(next.monthRolled());
        assertFalse(next.quarterRolled());
        assertFalse(next.yearRolled());
        assertEquals("2026-08", next.previousPeriod());
        assertEquals("2026-09", next.currentPeriod());
        assertEquals(2, next.newStateVersion());
        assertEquals(1, next.previousStateVersion());
    }

    @Test
    void quarterEndRollsQuarterAndMonthTogether() {
        Harness harness = harness();
        harness.rotation().check(at("2026-03-30T10:00:00+08:00"));
        TimeRotationService.RotationCheckResult next = harness.rotation().check(at("2026-04-01T10:00:00+08:00"));
        assertTrue(next.monthRolled());
        assertTrue(next.quarterRolled());
        assertFalse(next.halfYearRolled());
    }

    @Test
    void juneHalfYearBoundaryRollsHalfYear() {
        Harness harness = harness();
        harness.rotation().check(at("2026-06-30T10:00:00+08:00"));
        TimeRotationService.RotationCheckResult next = harness.rotation().check(at("2026-07-01T10:00:00+08:00"));
        assertTrue(next.monthRolled());
        assertTrue(next.halfYearRolled());
        assertFalse(next.yearRolled());
    }

    @Test
    void yearEndRollsYear() {
        Harness harness = harness();
        harness.rotation().check(at("2026-12-31T23:59:00+08:00"));
        TimeRotationService.RotationCheckResult next = harness.rotation().check(at("2027-01-01T00:01:00+08:00"));
        assertTrue(next.monthRolled());
        assertTrue(next.yearRolled());
        assertTrue(next.quarterRolled());
        assertTrue(next.halfYearRolled());
    }

    @Test
    void leapDayRollsIntoMarchWithoutFabrication() {
        Harness harness = harness();
        harness.rotation().check(at("2028-02-29T10:00:00+08:00"));
        TimeRotationService.RotationCheckResult next = harness.rotation().check(at("2028-03-01T10:00:00+08:00"));
        assertTrue(next.monthRolled());
        assertEquals("2028-03", next.currentPeriod());
        assertEquals("2028-02", next.previousPeriod());
    }

    @Test
    void forwardJumpIsDetectedAndNoFutureDataIsCreated() {
        Harness harness = harness();
        harness.rotation().check(at("2026-08-10T10:00:00+08:00"));
        TimeRotationService.RotationCheckResult next = harness.rotation().check(at("2026-08-15T10:00:00+08:00"));
        assertTrue(next.forwardJumpDetected());
        assertFalse(next.monthRolled());
        assertEquals(2, next.newStateVersion());
    }

    @Test
    void rollbackIsDetectedAndStateStillAdvancesMonotonically() {
        Harness harness = harness();
        harness.rotation().check(at("2026-08-10T10:00:00+08:00"));
        TimeRotationService.RotationCheckResult next = harness.rotation().check(at("2026-08-09T10:00:00+08:00"));
        assertTrue(next.rollbackDetected());
        assertEquals("2026-08", next.currentPeriod());
        assertEquals(2, next.newStateVersion());
        assertEquals(1, next.previousStateVersion());
    }

    @Test
    void restartRestoresPersistedStateAndContinuesFromCheckpoint() {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("rotation restart root"));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        Clock clock = Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), ZoneOffset.UTC);
        TimeStateStore storeA = new TimeStateStore(root, fileStore, clock);
        TimeRotationService serviceA = new TimeRotationService(storeA);
        serviceA.check(OffsetDateTime.parse("2026-08-10T10:00:00+08:00"));

        TimeStateStore storeB = new TimeStateStore(root, new AtomicFileStore(root, new DirtyMarkerCodec()), clock);
        TimeRotationService serviceB = new TimeRotationService(storeB);
        TimeRotationService.RotationCheckResult resumed = serviceB.check(OffsetDateTime.parse("2026-08-12T10:00:00+08:00"));
        assertFalse(resumed.firstRun());
        assertEquals(1, resumed.previousStateVersion());
        assertEquals(2, resumed.newStateVersion());
        assertTrue(resumed.forwardJumpDetected());
        assertEquals("2026-08", resumed.currentPeriod());
    }

    @Test
    void corruptTimeStateFailsClosed() throws Exception {
        Harness harness = harness();
        harness.rotation().check(at("2026-08-10T10:00:00+08:00"));
        java.nio.file.Files.writeString(harness.root().resolveDataRef(
                com.supplymind.foundation.storage.DataPaths.timeStateRef()),
                "{corrupt", java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(com.supplymind.foundation.storage.StorageException.class,
                () -> harness.rotation().check(at("2026-08-11T10:00:00+08:00")),
                "a corrupted time state must fail closed, never silently reset business processing");
    }

    private static OffsetDateTime at(String text) {
        return OffsetDateTime.parse(text);
    }

    private Harness harness() {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("rotation root " + System.nanoTime()));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        Clock clock = Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), ZoneOffset.UTC);
        TimeStateStore store = new TimeStateStore(root, fileStore, clock);
        TimeRotationService rotation = new TimeRotationService(store);
        return new Harness(root, rotation);
    }

    private record Harness(DataRoot root, TimeRotationService rotation) {
    }
}
