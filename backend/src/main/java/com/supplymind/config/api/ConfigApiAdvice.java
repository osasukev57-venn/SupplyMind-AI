package com.supplymind.config.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * D8-T01 unified MVC error handling (config + backfill API only - Day1-Day7 contracts
 * untouched). Missing parameters, malformed JSON bodies, type-conversion failures and any
 * config/backfill runtime failure ALL produce the same structured response:
 * HTTP 400 {status:"REJECTED", message} - never a 500, never a stack trace, never a
 * framework-default error body. Internal state such as file paths is never echoed.
 */
@RestControllerAdvice(basePackages = {
        "com.supplymind.config.api",
        "com.supplymind.backfill.api"
})
public class ConfigApiAdvice {

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

    /** Storage failures carry controlled server messages (capability gates etc.) - pass them through. */
    @ExceptionHandler(com.supplymind.foundation.storage.StorageException.class)
    public ResponseEntity<Map<String, String>> storageFailure(
            com.supplymind.foundation.storage.StorageException exception
    ) {
        return rejected(exception.getMessage() == null
                ? "config/backfill operation rejected" : exception.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> runtimeFailure(RuntimeException exception) {
        return rejected("config/backfill operation unavailable for the requested parameters");
    }

    private static ResponseEntity<Map<String, String>> rejected(String message) {
        return ResponseEntity.badRequest().body(Map.of(
                "status", "REJECTED",
                "message", message));
    }
}
