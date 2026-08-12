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
 * D5-T01/F1 rotation detection on top of a monotonic business high-water mark. The observed
 * wall clock is recorded only as a diagnostic fact; every rotation decision is computed from
 * the effective high-water time/business-date/period, which never move backwards. Rollback is
 * detected as observedTime &lt; effectiveHighWaterTime; a rollback never re-triggers a boundary
 * and never causes duplicate processing, because daily/aggregate/publish stay idempotent and
 * partition-correct. No business data is ever fabricated for a future period.
 */
public final class TimeRotationService {

    public static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final TimeStateStore stateStore;

    public TimeRotationService(TimeStateStore stateStore) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
    }

    public RotationCheckResult check(OffsetDateTime now) {
        Objects.requireNonNull(now, "now");
        LocalDate observedBusinessDate = now.atZoneSameInstant(SHANGHAI).toLocalDate();
        if (!stateStore.exists()) {
            TimeStateV1 initial = TimeStateV1.initial(now, observedBusinessDate);
            stateStore.write(initial);
            return RotationCheckResult.initial(initial);
        }
        TimeStateV1 previous = stateStore.read();
        boolean rollback = now.isBefore(previous.effectiveHighWaterTime());
        OffsetDateTime highWater = rollback ? previous.effectiveHighWaterTime() : now;
        LocalDate effectiveBusinessDate = observedBusinessDate.isAfter(previous.effectiveBusinessDate())
                ? observedBusinessDate
                : previous.effectiveBusinessDate();
        YearMonth previousMonth = YearMonth.parse(previous.lastCompletedPeriod());
        YearMonth currentMonth = YearMonth.from(effectiveBusinessDate);
        boolean monthRolled = !currentMonth.equals(previousMonth);
        long forwardDays = ChronoUnit.DAYS.between(previous.effectiveBusinessDate(), effectiveBusinessDate);
        boolean forwardJump = forwardDays > 1;
        TimeStateV1 next = new TimeStateV1(
                "1.0", previous.stateVersion() + 1, now, highWater,
                effectiveBusinessDate, currentMonth.toString(), now);
        stateStore.write(next);
        return new RotationCheckResult(
                false,
                previous.stateVersion(),
                next.stateVersion(),
                previous.lastCompletedPeriod(),
                currentMonth.toString(),
                effectiveBusinessDate,
                observedBusinessDate,
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

    /**
     * Deterministic observation result. `effectiveBusinessDate` is the monotonic high-water
     * business date used for rotation; `observedBusinessDate` is the raw clock business date
     * (diagnostic only). No business values are derived from this record itself.
     */
    public record RotationCheckResult(
            boolean firstRun,
            int previousStateVersion,
            int newStateVersion,
            String previousPeriod,
            String currentPeriod,
            LocalDate effectiveBusinessDate,
            LocalDate observedBusinessDate,
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
                    initial.effectiveBusinessDate(), initial.effectiveBusinessDate(),
                    false, false, false, false, false, false);
        }
    }
}
