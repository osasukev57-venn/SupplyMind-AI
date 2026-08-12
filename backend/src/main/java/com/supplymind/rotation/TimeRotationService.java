package com.supplymind.rotation;

import com.supplymind.foundation.model.TimeStateV1;
import com.supplymind.foundation.storage.TimeStateStore;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.Objects;

/**
 * D5-T01 rotation detection on top of the recoverable time state. Pure observation: it never
 * writes business files and never fabricates data. It detects period rollovers (month/quarter/
 * half-year/year), forward jumps (including sleep/resume gaps of more than one calendar day),
 * rollbacks and first-run initialization. Downstream idempotency (no duplicate publish/daily/
 * aggregate on rollback) is guaranteed by the existing deterministic processing chain, which
 * the rotation result only feeds with the authoritative current period.
 */
public final class TimeRotationService {

    public static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final TimeStateStore stateStore;

    public TimeRotationService(TimeStateStore stateStore) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
    }

    /** Observe one instant; persists the advanced state and reports the detected transitions. */
    public RotationCheckResult check(OffsetDateTime now) {
        Objects.requireNonNull(now, "now");
        LocalDate businessDate = now.atZoneSameInstant(SHANGHAI).toLocalDate();
        if (!stateStore.exists()) {
            TimeStateV1 initial = TimeStateV1.initial(now, businessDate);
            stateStore.write(initial);
            return RotationCheckResult.initial(initial);
        }
        TimeStateV1 previous = stateStore.read();
        YearMonth previousMonth = YearMonth.parse(previous.lastCompletedPeriod());
        YearMonth currentMonth = YearMonth.from(businessDate);
        boolean monthRolled = !currentMonth.equals(previousMonth);
        boolean rollback = now.isBefore(previous.lastObservedTime());
        long forwardDays = ChronoUnit.DAYS.between(previous.lastObservedBusinessDate(), businessDate);
        boolean forwardJump = forwardDays > 1;
        TimeStateV1 next = new TimeStateV1(
                "1.0", previous.stateVersion() + 1, now, businessDate,
                currentMonth.toString(), now);
        stateStore.write(next);
        return new RotationCheckResult(
                false,
                previous.stateVersion(),
                next.stateVersion(),
                previous.lastCompletedPeriod(),
                currentMonth.toString(),
                businessDate,
                rollback,
                forwardJump,
                monthRolled,
                monthRolled && quarterOf(previousMonth) != quarterOf(currentMonth),
                monthRolled && halfOf(previousMonth) != halfOf(currentMonth),
                monthRolled && previousMonth.getYear() != currentMonth.getYear());
    }

    /** Startup recovery: materialize the state if missing (idempotent). */
    public RotationCheckResult recover() {
        return check(OffsetDateTime.now(java.time.Clock.system(SHANGHAI)));
    }

    private static int quarterOf(YearMonth month) {
        return month.get(IsoFields.QUARTER_OF_YEAR);
    }

    private static int halfOf(YearMonth month) {
        return month.getMonthValue() <= 6 ? 1 : 2;
    }

    /** Deterministic observation result; no business values are derived from it. */
    public record RotationCheckResult(
            boolean firstRun,
            int previousStateVersion,
            int newStateVersion,
            String previousPeriod,
            String currentPeriod,
            LocalDate businessDate,
            boolean rollbackDetected,
            boolean forwardJumpDetected,
            boolean monthRolled,
            boolean quarterRolled,
            boolean halfYearRolled,
            boolean yearRolled
    ) {
        static RotationCheckResult initial(TimeStateV1 initial) {
            return new RotationCheckResult(true, 0, initial.stateVersion(),
                    initial.lastCompletedPeriod(), initial.lastCompletedPeriod(),
                    initial.lastObservedBusinessDate(), false, false, false, false, false, false);
        }
    }
}
