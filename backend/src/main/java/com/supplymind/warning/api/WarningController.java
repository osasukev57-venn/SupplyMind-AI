package com.supplymind.warning.api;

import com.supplymind.warning.WarningAcknowledgementV1;
import com.supplymind.warning.WarningAckStore;
import com.supplymind.warning.WarningQueryService;
import com.supplymind.warning.WarningRecordV1;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * D8-T02 warning API. Evaluation reuses the frozen WarningService (deterministic, demoRule
 * only, EXT-07/EXT-08 stay open); listing uses the WarningQueryService real from/to range;
 * acknowledgement writes a DEC-061 sidecar through WarningAckStore - the original warning
 * evidence is never modified. The controller never scans the filesystem itself and never
 * computes risk levels or thresholds.
 */
@RestController
@RequestMapping("/api/warnings")
public class WarningController {

    private final WarningService warnings;
    private final WarningQueryService query;
    private final WarningAckStore ackStore;

    public WarningController(WarningService warnings, WarningQueryService query, WarningAckStore ackStore) {
        this.warnings = warnings;
        this.query = query;
        this.ackStore = ackStore;
    }

    /** Request-driven evaluation (v1 demo rules only). */
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
        WarningAcknowledgementV1 ack = ackStore.acknowledge(
                warning, request.dispositionNote(), java.time.OffsetDateTime.now());
        return ResponseEntity.ok(WarningV1.toView(ack));
    }

    /** Read the existing acknowledgement sidecar (null-safe). */
    @GetMapping("/{warningId}/ack")
    public ResponseEntity<?> ack(
            @RequestParam("itemId") String itemId,
            @PathVariable("warningId") String warningId
    ) {
        WarningRecordV1 warning = query.findByWarningId(itemId, warningId)
                .orElseThrow(() -> new IllegalArgumentException("unknown warningId"));
        if (!ackStore.exists(query.ackRefOf(warning), warning.warningId())) {
            return ResponseEntity.ok(Map.of("acknowledged", false));
        }
        return ResponseEntity.ok(Map.of(
                "acknowledged", true,
                "ack", WarningV1.toView(ackStore.read(query.ackRefOf(warning), warning.warningId()))));
    }

    private static WarningRuleV1 toDemoRule(WarningV1.EvaluateRequest request) {
        return new WarningRuleV1(
                request.ruleId(), request.ruleVersion() == null ? "demo-v1" : request.ruleVersion(),
                WarningRuleV1.RuleKind.valueOf(request.ruleKind()),
                request.itemId(), request.grain(), request.threshold(),
                WarningRuleV1.Direction.valueOf(request.direction()),
                request.baselinePeriods() == null ? 1 : request.baselinePeriods(),
                true, // v1 evaluate accepts demoRule=true only; EXT-07/EXT-08 stay open
                request.description() == null
                        ? "TEST/DEMO threshold - not a final business threshold (EXT-07/EXT-08 open)"
                        : request.description());
    }
}
