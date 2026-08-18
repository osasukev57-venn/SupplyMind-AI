package com.supplymind.dashboard.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * D7 unified MVC error handling (dashboard package only - Agent and Day1-Day6 contracts are
 * untouched). Missing parameters, type-conversion failures (non-numeric years), malformed
 * dates and any dashboard runtime failure ALL produce the same structured response:
 * HTTP 400 {status:"REJECTED", message} - never a 500, never a stack trace, never a
 * framework-default error body.
 */
@RestControllerAdvice(basePackages = "com.supplymind.dashboard")
public class DashboardApiAdvice {

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, String>> missingParameter(
            MissingServletRequestParameterException exception
    ) {
        return rejected("required parameter '" + exception.getParameterName() + "' is missing");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> typeMismatch(
            MethodArgumentTypeMismatchException exception
    ) {
        return rejected("parameter '" + exception.getName() + "' has an invalid value");
    }

    @ExceptionHandler(DateTimeParseException.class)
    public ResponseEntity<Map<String, String>> malformedDate(DateTimeParseException exception) {
        return rejected("from/to must be ISO yyyy-MM-dd dates");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> illegalArgument(IllegalArgumentException exception) {
        return rejected(exception.getMessage() == null
                ? "invalid request parameters" : exception.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> runtimeFailure(RuntimeException exception) {
        return rejected("dashboard data unavailable for the requested parameters");
    }

    private static ResponseEntity<Map<String, String>> rejected(String message) {
        return ResponseEntity.badRequest().body(Map.of(
                "status", "REJECTED",
                "message", message));
    }
}
