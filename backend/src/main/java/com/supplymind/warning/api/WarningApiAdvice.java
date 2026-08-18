package com.supplymind.warning.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * D8-T02 unified MVC error handling (warning API only - Day1-Day7 contracts untouched).
 * Missing parameters, malformed JSON, bad dates, unknown warningId and any storage failure
 * all produce the same structured response: HTTP 400 {status:"REJECTED", message} - never a
 * 500, never a stack trace, never a framework-default error body.
 */
@RestControllerAdvice(basePackages = "com.supplymind.warning.api")
public class WarningApiAdvice {

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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadableBody(HttpMessageNotReadableException exception) {
        Throwable cause = exception.getMostSpecificCause();
        if (cause instanceof IllegalArgumentException controlled) {
            return rejected(controlled.getMessage() == null
                    ? "request body must be a valid JSON object" : controlled.getMessage());
        }
        return rejected("request body must be a valid JSON object");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> illegalArgument(IllegalArgumentException exception) {
        return rejected(exception.getMessage() == null
                ? "invalid request parameters" : exception.getMessage());
    }

    @ExceptionHandler(com.supplymind.foundation.storage.StorageException.class)
    public ResponseEntity<Map<String, String>> storageFailure(
            com.supplymind.foundation.storage.StorageException exception
    ) {
        return rejected(exception.getMessage() == null
                ? "warning operation rejected" : exception.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> runtimeFailure(RuntimeException exception) {
        return rejected("warning operation unavailable for the requested parameters");
    }

    private static ResponseEntity<Map<String, String>> rejected(String message) {
        return ResponseEntity.badRequest().body(Map.of(
                "status", "REJECTED",
                "message", message));
    }
}
