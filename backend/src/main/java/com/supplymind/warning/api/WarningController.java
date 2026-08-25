package com.supplymind.warning.api;

import com.supplymind.warning.WarningAcknowledgementV1;
import com.supplymind.warning.WarningAckStore;
import com.supplymind.warning.WarningQueryService;
import com.supplymind.warning.WarningRecordV1;
import com.supplymind.warning.WarningRuleConfigV1;
import com.supplymind.warning.WarningRuleManagementService;
import com.supplymind.warning.WarningRuleV1;
import com.supplymind.warning.WarningService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Warning API. Formal default/custom rules use deterministic Java evaluation over published data;
 * the legacy demo endpoint remains for compatibility. Listing uses an exact date range and
 * acknowledgements stay in DEC-061 sidecars without rewriting warning evidence.
 */
@RestController
@RequestMapping("/api/warnings")
public class WarningController {

    private final WarningService warnings;
    private final WarningQueryService query;
    private final WarningAckStore ackStore;
    private final java.time.Clock clock;
    private final WarningRuleManagementService ruleManagement;

    public WarningController(
            WarningService warnings,
            WarningQueryService query,
            WarningAckStore ackStore,
            java.time.Clock clock,
            WarningRuleManagementService ruleManagement
    ) {
        this.warnings = warnings;
        this.query = query;
        this.ackStore = ackStore;
        this.clock = clock;
        this.ruleManagement = ruleManagement;
    }

    /** Effective rules for every enabled monitored series (defaults plus user overrides). */
    @GetMapping("/rules")
    public ResponseEntity<?> rules() {
        return ResponseEntity.ok(Map.of("rules", requireRuleManagement().listRules().stream()
                .map(WarningV1::toView).toList()));
    }

    /** Save one per-series override and immediately perform a formal evaluation. */
    @PostMapping("/rules/{itemId}")
    public ResponseEntity<?> updateRule(
            @PathVariable("itemId") String itemId,
            @RequestBody WarningV1.UpdateRuleRequest request
    ) {
        WarningRuleManagementService management = requireRuleManagement();
        WarningRuleManagementService.BoundRule rule = management.update(
                itemId, request.lookbackDays(),
                new java.math.BigDecimal(request.thresholdPercent()).movePointLeft(2).toPlainString(),
                WarningRuleConfigV1.Direction.valueOf(request.direction()),
                request.enabled(), request.description());
        return ResponseEntity.ok(Map.of(
                "rule", WarningV1.toView(rule),
                "evaluation", WarningV1.toView(management.evaluateItem(itemId))));
    }

    /** Remove the override, restore the type default and evaluate it immediately. */
    @PostMapping("/rules/{itemId}/reset")
    public ResponseEntity<?> resetRule(@PathVariable("itemId") String itemId) {
        WarningRuleManagementService management = requireRuleManagement();
        WarningRuleManagementService.BoundRule rule = management.reset(itemId);
        return ResponseEntity.ok(Map.of(
                "rule", WarningV1.toView(rule),
                "evaluation", WarningV1.toView(management.evaluateItem(itemId))));
    }

    /** Evaluate all enabled rules against the latest published data. */
    @PostMapping("/rules/evaluate-all")
    public ResponseEntity<?> evaluateAllRules() {
        return ResponseEntity.ok(Map.of("evaluations", requireRuleManagement().evaluateAll().stream()
                .map(WarningV1::toView).toList()));
    }
    /** Compatibility endpoint for legacy v1 demo rules; the product UI uses /rules/evaluate-all. */
    @PostMapping("/evaluate")
    public ResponseEntity<?> evaluate(@RequestBody WarningV1.EvaluateRequest request) {
        WarningRuleV1 rule = toDemoRule(request);
        WarningRecordV1 record = warnings.evaluate(rule, request.periodStart(), request.periodEnd());
        if (record == null) {
            return ResponseEntity.ok(Map.of("status", "NOT_TRIGGERED",
                    "message", "no warning triggered for the requested rule and period",
                    "ruleId", request.ruleId()));
        }
        return ResponseEntity.ok(Map.of(
                "status", "TRIGGERED",
                "warning", WarningV1.toView(record, query.isAcknowledged(record), query.ackRefOf(record))));
    }

    /** Real from/to warning listing (exact range semantics, never a fixed lookback). */
    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam("itemId") String itemId,
            @RequestParam("from") String from,
            @RequestParam("to") String to
    ) {
        LocalDate fromDate = LocalDate.parse(from);
        LocalDate toDate = LocalDate.parse(to);
        List<WarningV1.WarningView> views = new ArrayList<>();
        for (WarningRecordV1 warning : query.queryByRange(itemId, fromDate, toDate)) {
            views.add(WarningV1.toView(warning, query.isAcknowledged(warning), query.ackRefOf(warning)));
        }
        return ResponseEntity.ok(Map.of("itemId", itemId, "from", from, "to", to, "warnings", views));
    }

    /** One warning detail. */
    @GetMapping("/{warningId}")
    public ResponseEntity<?> detail(
            @RequestParam("itemId") String itemId,
            @PathVariable("warningId") String warningId
    ) {
        WarningRecordV1 warning = query.findByWarningId(itemId, warningId)
                .orElseThrow(() -> new IllegalArgumentException("unknown warningId"));
        return ResponseEntity.ok(WarningV1.toView(warning, query.isAcknowledged(warning), query.ackRefOf(warning)));
    }

    /** Acknowledge one warning (DEC-061 sidecar; the original evidence stays immutable). */
    @PostMapping("/{warningId}/ack")
    public ResponseEntity<?> acknowledge(
            @RequestParam("itemId") String itemId,
            @PathVariable("warningId") String warningId,
            @RequestBody WarningV1.AckRequest request
    ) {
        WarningRecordV1 warning = query.findByWarningId(itemId, warningId)
                .orElseThrow(() -> new IllegalArgumentException("unknown warningId"));
        // M2: acknowledgedAt is generated by the INJECTED server Clock - never by the client,
        // never a direct OffsetDateTime.now() in the controller.
        WarningAcknowledgementV1 ack = ackStore.acknowledge(
                warning, request.dispositionNote(), OffsetDateTime.now(clock));
        return ResponseEntity.ok(WarningV1.toView(ack));
    }

    /**
     * Read the existing acknowledgement sidecar through the SINGLE authoritative DEC-061
     * verification entry. Any binding violation fails closed (StorageException -> 400
     * REJECTED); a missing sidecar is a genuine not-yet-acknowledged record.
     */
    @GetMapping("/{warningId}/ack")
    public ResponseEntity<?> ack(
            @RequestParam("itemId") String itemId,
            @PathVariable("warningId") String warningId
    ) {
        WarningRecordV1 warning = query.findByWarningId(itemId, warningId)
                .orElseThrow(() -> new IllegalArgumentException("unknown warningId"));
        java.util.Optional<WarningAcknowledgementV1> verified = ackStore.readVerified(warning);
        if (verified.isEmpty()) {
            return ResponseEntity.ok(Map.of("acknowledged", false));
        }
        return ResponseEntity.ok(Map.of(
                "acknowledged", true,
                "ack", WarningV1.toView(verified.get())));
    }

    private WarningRuleManagementService requireRuleManagement() {
        if (ruleManagement == null) {
            throw new IllegalStateException("formal warning-rule management is unavailable");
        }
        return ruleManagement;
    }
    private static WarningRuleV1 toDemoRule(WarningV1.EvaluateRequest request) {
        return new WarningRuleV1(
                request.ruleId(), request.ruleVersion() == null ? "demo-v1" : request.ruleVersion(),
                WarningRuleV1.RuleKind.valueOf(request.ruleKind()),
                request.itemId(), request.grain(), request.threshold(),
                WarningRuleV1.Direction.valueOf(request.direction()),
                request.baselinePeriods() == null ? 1 : request.baselinePeriods(),
                true, // Legacy v1 stays demo-only; DEC-064 formal rules use WarningRuleConfigV1.
                request.description() == null
                        ? "TEST/DEMO threshold - not a DEC-064 formal warning rule"
                        : request.description());
    }
}
