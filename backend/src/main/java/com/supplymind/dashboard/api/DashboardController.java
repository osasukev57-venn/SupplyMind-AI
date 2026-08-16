package com.supplymind.dashboard.api;

import com.supplymind.dashboard.DashboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * D7 read-only Dashboard API plus the Day8-boundary PENDING accept endpoints. Every business
 * value and status is computed by the Java backend (DashboardService -> existing services);
 * the Vue layer only renders the returned strings. Errors are ALWAYS structured 400
 * {status:REJECTED, message} - invalid parameters and unavailable data never produce a 500
 * with a raw stack trace.
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

    /** D7 M1: manual intake accept-into-PENDING (real boundary, structured response). */
    @PostMapping("/manual")
    public ResponseEntity<?> manual(@RequestParam Map<String, String> body) {
        return okOrRejected(() -> dashboard.manualPending(
                body.get("itemId"), body.get("source"), body.get("businessDate"),
                body.get("value"), body.get("unit")));
    }

    /** D7 M1: file import through the real LocalImport boundary (CSV and XLSX really parsed). */
    @PostMapping("/import")
    public ResponseEntity<?> importFile(@RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(dashboard.importPending(
                    file.getOriginalFilename(), file.getBytes()));
        } catch (java.io.IOException exception) {
            return rejected("file upload could not be read");
        } catch (IllegalArgumentException exception) {
            return rejected(exception.getMessage() == null
                    ? "invalid request parameters" : exception.getMessage());
        } catch (RuntimeException exception) {
            return rejected("dashboard data unavailable for the requested parameters");
        }
    }

    /** D7: frozen import CSV template download (the exact LocalImport boundary header). */
    @GetMapping("/import/template")
    public ResponseEntity<byte[]> importTemplate() {
        byte[] body = dashboard.importTemplate()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header("Content-Type", "text/csv; charset=UTF-8")
                .header("Content-Disposition",
                        "attachment; filename=\"import-template.csv\"")
                .body(body);
    }

    /** D7 M1: synthetic demo entry - real deterministic demo generation (never persisted). */
    @PostMapping("/synthetic-demo")
    public ResponseEntity<?> syntheticDemo() {
        return okOrRejected(dashboard::syntheticDemo);
    }

    /**
     * D7 unified error contract: invalid parameters (unknown itemId, from > to, oversized range,
     * malformed dates) are 400 REJECTED with the controlled service message; any other failure
     * is also a 400 REJECTED with a generic message - a 500 is never returned by the dashboard
     * API. No error response ever leaks a stack trace.
     */
    private static ResponseEntity<?> okOrRejected(java.util.function.Supplier<?> supplier) {
        try {
            return ResponseEntity.ok(supplier.get());
        } catch (IllegalArgumentException exception) {
            return rejected(exception.getMessage() == null
                    ? "invalid request parameters" : exception.getMessage());
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
