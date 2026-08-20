package com.supplymind.manual.api;

import com.supplymind.manual.ManualMaterialProcessingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/manual")
public final class ManualMaterialProcessingController {

    private final ManualMaterialProcessingService processing;

    public ManualMaterialProcessingController(ManualMaterialProcessingService processing) {
        this.processing = processing;
    }

    @PostMapping("/{runId}/process")
    public ResponseEntity<?> process(@PathVariable("runId") String runId) {
        try {
            return ResponseEntity.ok(processing.process(runId));
        } catch (IllegalArgumentException exception) {
            return rejected(exception.getMessage());
        } catch (RuntimeException exception) {
            return rejected("Manual input could not be processed");
        }
    }

    private static ResponseEntity<Map<String, String>> rejected(String message) {
        return ResponseEntity.badRequest().body(Map.of(
                "status", "REJECTED",
                "message", message == null ? "Manual input could not be processed" : message));
    }
}
