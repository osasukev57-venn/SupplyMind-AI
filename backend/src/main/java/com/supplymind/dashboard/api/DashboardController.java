package com.supplymind.dashboard.api;

import com.supplymind.dashboard.DashboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * D7 read-only Dashboard API. Every business value and status is computed by the Java backend
 * (DashboardService -> existing services); the Vue layer only renders the returned strings.
 * Errors are structured HTTP responses - never a raw stack trace.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/overview")
    public ResponseEntity<?> overview() {
        try {
            return ResponseEntity.ok(dashboard.overview());
        } catch (RuntimeException exception) {
            return unavailable(exception);
        }
    }

    @GetMapping("/history")
    public ResponseEntity<?> history(
            @RequestParam("itemId") String itemId,
            @RequestParam("from") String from,
            @RequestParam("to") String to
    ) {
        try {
            return ResponseEntity.ok(dashboard.history(itemId, from, to));
        } catch (DateTimeParseException exception) {
            return badRequest("from/to must be ISO yyyy-MM-dd dates");
        } catch (RuntimeException exception) {
            return unavailable(exception);
        }
    }

    @GetMapping("/metrics")
    public ResponseEntity<?> metrics(
            @RequestParam("itemId") String itemId,
            @RequestParam("grain") String grain,
            @RequestParam("fromYear") int fromYear,
            @RequestParam("toYear") int toYear
    ) {
        try {
            return ResponseEntity.ok(dashboard.metrics(itemId, grain, fromYear, toYear));
        } catch (RuntimeException exception) {
            return unavailable(exception);
        }
    }

    @GetMapping("/quality")
    public ResponseEntity<?> quality(
            @RequestParam("itemId") String itemId,
            @RequestParam("from") String from,
            @RequestParam("to") String to
    ) {
        try {
            return ResponseEntity.ok(dashboard.quality(itemId, from, to));
        } catch (DateTimeParseException exception) {
            return badRequest("from/to must be ISO yyyy-MM-dd dates");
        } catch (RuntimeException exception) {
            return unavailable(exception);
        }
    }

    @GetMapping("/sources")
    public ResponseEntity<?> sources() {
        try {
            return ResponseEntity.ok(dashboard.sources());
        } catch (RuntimeException exception) {
            return unavailable(exception);
        }
    }

    private static ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of(
                "status", "REJECTED",
                "message", message));
    }

    private static ResponseEntity<Map<String, String>> unavailable(RuntimeException exception) {
        return ResponseEntity.internalServerError().body(Map.of(
                "status", "UNAVAILABLE",
                "message", "dashboard data unavailable"));
    }
}
