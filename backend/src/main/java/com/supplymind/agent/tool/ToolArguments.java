package com.supplymind.agent.tool;

import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.StorageException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * D6-T01 safe tool argument parsing. Only business parameters are accepted: identifiers
 * (DataPaths.requireIdentifier semantics), ISO dates, frozen grains and year-month strings.
 * Anything that looks like a file path (absolute paths, drive letters, "..", backslashes, "~")
 * or an oversized range is rejected as REJECTED - tools never accept arbitrary file paths and
 * never allow unbounded ranges.
 */
public final class ToolArguments {

    private static final Pattern GRAINS = Pattern.compile("month|quarter|halfyear|year");
    private static final List<String> GRAIN_LIST = List.of("month", "quarter", "halfyear", "year");
    private static final int MAX_RANGE_DAYS = 3660; // 10 years of daily range at most

    private ToolArguments() {
    }

    public static String identifier(String value, String fieldName, String toolName) {
        if (value == null || value.isBlank()) {
            throw new ToolInputException(toolName, "missing required argument: " + fieldName);
        }
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.indexOf("..") >= 0
                || value.startsWith("~") || value.startsWith(".") || value.indexOf(':') >= 0
                || value.contains(" ") || value.contains("\t") || value.contains("\n")
                || value.contains("\r") || value.contains("\"") || value.contains("'")) {
            throw new ToolInputException(toolName, fieldName + " must be a plain business identifier, not a path");
        }
        try {
            DataPaths.requireIdentifier(value, fieldName);
        } catch (StorageException exception) {
            throw new ToolInputException(toolName, exception.getMessage());
        }
        return value;
    }

    public static LocalDate date(String value, String fieldName, String toolName) {
        if (value == null || value.isBlank()) {
            throw new ToolInputException(toolName, "missing required argument: " + fieldName);
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ToolInputException(toolName, fieldName + " must be an ISO date (yyyy-MM-dd): " + value);
        }
    }

    public static void range(LocalDate from, LocalDate to, String toolName) {
        if (from.isAfter(to)) {
            throw new ToolInputException(toolName, "from must not be after to");
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to);
        if (days > MAX_RANGE_DAYS) {
            throw new ToolInputException(toolName, "date range too large (max " + MAX_RANGE_DAYS + " days)");
        }
    }

    public static YearMonth yearMonth(String value, String fieldName, String toolName) {
        if (value == null || value.isBlank()) {
            throw new ToolInputException(toolName, "missing required argument: " + fieldName);
        }
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ToolInputException(toolName, fieldName + " must be yyyy-MM: " + value);
        }
    }

    public static String grain(String value, String toolName) {
        if (value == null || value.isBlank()) {
            throw new ToolInputException(toolName, "missing required argument: grain");
        }
        if (!GRAINS.matcher(value).matches() || !GRAIN_LIST.contains(value)) {
            throw new ToolInputException(toolName, "grain must be one of month/quarter/halfyear/year: " + value);
        }
        return value;
    }

    public static int year(String value, String fieldName, String toolName) {
        try {
            int year = Integer.parseInt(value);
            if (year < 2000 || year > 2100) {
                throw new ToolInputException(toolName, fieldName + " must be between 2000 and 2100");
            }
            return year;
        } catch (NumberFormatException exception) {
            throw new ToolInputException(toolName, fieldName + " must be an integer year");
        }
    }
}
