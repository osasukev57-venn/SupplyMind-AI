package com.supplymind.processing;

import com.supplymind.foundation.model.AggregateGrain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

/** Natural calendar period boundaries for the frozen aggregate grains (month/quarter/halfyear/year). */
public final class PeriodBoundaries {

    private PeriodBoundaries() {
    }

    public static LocalDate periodStart(AggregateGrain grain, int year, int periodIndex) {
        Objects.requireNonNull(grain, "grain");
        switch (grain) {
            case MONTH:
                return YearMonth.of(year, periodIndex).atDay(1);
            case QUARTER:
                return LocalDate.of(year, quarterStartMonth(periodIndex), 1);
            case HALFYEAR:
                return LocalDate.of(year, halfYearStartMonth(periodIndex), 1);
            case YEAR:
                return LocalDate.of(year, 1, 1);
            default:
                throw new IllegalArgumentException("Unsupported grain: " + grain);
        }
    }

    public static LocalDate periodEnd(AggregateGrain grain, int year, int periodIndex) {
        Objects.requireNonNull(grain, "grain");
        switch (grain) {
            case MONTH:
                return YearMonth.of(year, periodIndex).atEndOfMonth();
            case QUARTER:
                return YearMonth.of(year, quarterStartMonth(periodIndex) + 2).atEndOfMonth();
            case HALFYEAR:
                return YearMonth.of(year, halfYearStartMonth(periodIndex) + 5).atEndOfMonth();
            case YEAR:
                return YearMonth.of(year, 12).atEndOfMonth();
            default:
                throw new IllegalArgumentException("Unsupported grain: " + grain);
        }
    }

    public static LocalDate periodStart(AggregateGrain grain, LocalDate anchor) {
        Objects.requireNonNull(anchor, "anchor");
        int year = anchor.getYear();
        switch (grain) {
            case MONTH:
                return YearMonth.from(anchor).atDay(1);
            case QUARTER:
                return LocalDate.of(year, quarterStartMonth(quarterOf(anchor)), 1);
            case HALFYEAR:
                return LocalDate.of(year, halfYearStartMonth(halfYearOf(anchor)), 1);
            case YEAR:
                return LocalDate.of(year, 1, 1);
            default:
                throw new IllegalArgumentException("Unsupported grain: " + grain);
        }
    }

    public static LocalDate periodEnd(AggregateGrain grain, LocalDate anchor) {
        Objects.requireNonNull(anchor, "anchor");
        int year = anchor.getYear();
        switch (grain) {
            case MONTH:
                return YearMonth.from(anchor).atEndOfMonth();
            case QUARTER:
                return YearMonth.of(year, quarterStartMonth(quarterOf(anchor)) + 2).atEndOfMonth();
            case HALFYEAR:
                return YearMonth.of(year, halfYearStartMonth(halfYearOf(anchor)) + 5).atEndOfMonth();
            case YEAR:
                return YearMonth.of(year, 12).atEndOfMonth();
            default:
                throw new IllegalArgumentException("Unsupported grain: " + grain);
        }
    }

    public static int periodIndex(AggregateGrain grain, LocalDate anchor) {
        Objects.requireNonNull(anchor, "anchor");
        switch (grain) {
            case MONTH:
                return anchor.getMonthValue();
            case QUARTER:
                return quarterOf(anchor);
            case HALFYEAR:
                return halfYearOf(anchor);
            case YEAR:
                return 1;
            default:
                throw new IllegalArgumentException("Unsupported grain: " + grain);
        }
    }

    private static int quarterStartMonth(int quarter) {
        requireRange(quarter, 1, 4, "quarter");
        return (quarter - 1) * 3 + 1;
    }

    private static int halfYearStartMonth(int halfYear) {
        requireRange(halfYear, 1, 2, "halfyear");
        return (halfYear - 1) * 6 + 1;
    }

    private static int quarterOf(LocalDate date) {
        return (date.getMonthValue() - 1) / 3 + 1;
    }

    private static int halfYearOf(LocalDate date) {
        return date.getMonthValue() <= 6 ? 1 : 2;
    }

    private static void requireRange(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " index out of range " + min + ".." + max + ": " + value);
        }
    }
}
