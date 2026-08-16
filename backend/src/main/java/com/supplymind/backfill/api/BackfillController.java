package com.supplymind.backfill.api;

import com.supplymind.config.DynamicConfigWorkflowService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * D8-T01 backfill task API. Thin adapter over DynamicConfigWorkflowService: the controller
 * never scans runtime/jobs, never constructs job ids and never orchestrates acquisition -
 * create/resume/run/retry all delegate to the existing BackfillOrchestrator through the
 * workflow service. Failures are structured {status,message} via ConfigApiAdvice.
 */
@RestController
@RequestMapping("/api/backfill")
public class BackfillController {

    private final DynamicConfigWorkflowService workflow;

    public BackfillController(DynamicConfigWorkflowService workflow) {
        this.workflow = workflow;
    }

    /** All persisted backfill jobs (manifest-verified read). */
    @GetMapping("/jobs")
    public ResponseEntity<?> jobs() {
        return ok(Map.of("jobs", workflow.backfillJobs()));
    }

    /** One persisted backfill job by id. */
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<?> job(@PathVariable("jobId") String jobId) {
        return ok(workflow.backfillJob(jobId));
    }

    /** Create or resume a backfill job for an existing target (idempotent; starts WAITING). */
    @PostMapping("/jobs")
    public ResponseEntity<?> createJob(
            @RequestParam("itemId") String itemId,
            @RequestParam("from") String from,
            @RequestParam("to") String to
    ) {
        return ok(workflow.createBackfillJob(itemId, from, to));
    }

    /** Run a backfill job through the real orchestrator (checkpoint-resumed). */
    @PostMapping("/jobs/{jobId}/run")
    public ResponseEntity<?> runJob(@PathVariable("jobId") String jobId) {
        return ok(workflow.runBackfill(jobId));
    }

    /** Retry: reopen a FAILED job to WAITING and resume; non-terminal jobs resume as-is. */
    @PostMapping("/jobs/{jobId}/retry")
    public ResponseEntity<?> retryJob(@PathVariable("jobId") String jobId) {
        return ok(workflow.retryBackfill(jobId));
    }

    private static ResponseEntity<?> ok(Object body) {
        return ResponseEntity.ok(body);
    }
}
