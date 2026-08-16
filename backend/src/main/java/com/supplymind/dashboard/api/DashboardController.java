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
 * Errors are ALWAYS structured 400 {status:REJECTED, message} - invalid parameters and
 * unavailable data never produce a 500 with a raw stack trace.
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
        return okOrRejected(() -> dashboard.overview());
    }

    @GetMapping("/history")
    public ResponseEntity<?> history(
            @RequestParam("itemId") String itemId,
            @RequestParam("from") String from,
            @RequestParam("to") String to
    ) {
        return okOrRejected(() -> dashboard.history(itemId, from, to));
    }

    @GetMapping("/metrics")
    public ResponseEntity<?> metrics(
            @RequestParam("itemId") String itemId,
            @RequestParam("grain") String grain,
            @RequestParam("fromYear") int fromYear,
            @RequestParam("toYear") int toYear
    ) {
        return okOrRejected(() -> dashboard.metrics(itemId, grain, fromYear, toYear));
    }

    @GetMapping("/quality")
    public ResponseEntity<?> quality(
            @RequestParam("itemId") String itemId,
            @RequestParam("from") String from,
            @RequestParam("to") String to
    ) {
        return okOrRejected(() -> dashboard.quality(itemId, from, to));
    }

    @GetMapping("/sources")
    public ResponseEntity<?> sources() {
        return okOrRejected(dashboard::sources);
    }

    /**
     * D7 unified error contract: every failure is a 400 REJECTED with a controlled message -
     * a 500 is never returned by the dashboard API.
     */
    private static ResponseEntity<?> okOrRejected(java.util.function.Supplier<?> supplier) {
        try {
            return ResponseEntity.ok(supplier.get());
        } catch (DateTimeParseException exception) {
            return rejected("from/to must be ISO yyyy-MM-dd dates");
        } catch (RuntimeException exception) {
            return rejected("dashboard data unavailable for the requested parameters");
        }
    }

    private static ResponseEntity<Map<String, String>> rejected(String message) {
        return ResponseEntity.badRequest().body(Map.of(
                "status", "REJECTED",
                "message", message));
    }
}
