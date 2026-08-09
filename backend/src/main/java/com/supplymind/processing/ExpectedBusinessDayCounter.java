package com.supplymind.processing;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Frozen calendarVersion expected-business-date counts (CALCULATION-RULES/DEC-054):
 * weekday-asia-shanghai-v1 counts Monday-Friday in Asia/Shanghai natural days;
 * golden-calendar-v1 (GD-01 fixture only) counts the 10th and 20th of each month.
 */
public final class ExpectedBusinessDayCounter {

    private ExpectedBusinessDayCounter() {
    }

    public static int expectedCount(String calendarVersion, LocalDate periodStart, LocalDate periodEnd) {
        if ("weekday-asia-shanghai-v1".equals(calendarVersion)) {
            return weekdaysBetween(periodStart, periodEnd);
        }
        if ("golden-calendar-v1".equals(calendarVersion)) {
            return goldenDaysBetween(periodStart, periodEnd);
        }
        throw new IllegalArgumentException("Unsupported calendarVersion: " + calendarVersion);
    }

    private static int weekdaysBetween(LocalDate start, LocalDate end) {
        int count = 0;
        LocalDate current = start;
        while (!current.isAfter(end)) {
            DayOfWeek day = current.getDayOfWeek();
            if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) {
                count++;
            }
            current = current.plusDays(1);
        }
        return count;
    }

    private static int goldenDaysBetween(LocalDate start, LocalDate end) {
        int count = 0;
        LocalDate current = start;
        while (!current.isAfter(end)) {
            if (current.getDayOfMonth() == 10 || current.getDayOfMonth() == 20) {
                count++;
            }
            current = current.plusDays(1);
        }
        return count;
    }
}
