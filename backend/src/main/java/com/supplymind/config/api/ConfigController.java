package com.supplymind.config.api;

import com.supplymind.config.DynamicConfigWorkflowService;
import com.supplymind.config.api.ConfigV1.AddItemRequest;
import com.supplymind.config.api.ConfigV1.ReplaceItemRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * D8-T01 dynamic configuration API. The controller is a thin adapter over
 * DynamicConfigWorkflowService: it never constructs configVersion, routeEffectiveAt, job ids or
 * audit times, never reads business files and never orchestrates cross-module business. All
 * failures are structured 400/500 {status,message} responses via ConfigApiAdvice.
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final DynamicConfigWorkflowService workflow;

    public ConfigController(DynamicConfigWorkflowService workflow) {
        this.workflow = workflow;
    }

    /** Current active configuration (configuration-driven panel rebuild input). */
    @GetMapping("/items")
    public ResponseEntity<?> items() {
        return ok(workflow.configView());
    }

    /** Read-only configVersion audit trail (config/history snapshots). */
    @GetMapping("/history")
    public ResponseEntity<?> history() {
        return ok(workflow.configHistory());
    }

    /** Secret-free provider capability projection. */
    @GetMapping("/capabilities")
    public ResponseEntity<?> capabilities() {
        return ok(Map.of("providers", workflow.capabilities()));
    }

    /** ADD a new monitored target; the backend validates capability and activates. */
    @PostMapping("/items")
    public ResponseEntity<?> addItem(@RequestBody AddItemRequest request) {
        return ok(workflow.addItem(request));
    }

    /** ENABLE/DISABLE an existing target (pure activation, history untouched). */
    @PostMapping("/items/{itemId}/enabled")
    public ResponseEntity<?> setEnabled(
            @PathVariable("itemId") String itemId,
            @RequestParam("enabled") boolean enabled
    ) {
        return ok(workflow.setEnabled(itemId, enabled));
    }

    /** REPLACE: disable old target (history preserved) + activate the replacement. */
    @PostMapping("/replace")
    public ResponseEntity<?> replace(@RequestBody ReplaceItemRequest request) {
        return ok(workflow.replaceItem(request));
    }

    private static ResponseEntity<?> ok(Object body) {
        return ResponseEntity.ok(body);
    }
}
