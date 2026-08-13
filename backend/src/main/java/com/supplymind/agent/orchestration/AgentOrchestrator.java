package com.supplymind.agent.orchestration;

import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.agent.evidence.EvidenceRefVerifier;
import com.supplymind.agent.fallback.TemplateFallbackService;
import com.supplymind.agent.llm.LLMService;
import com.supplymind.agent.report.AgentReportV1;
import com.supplymind.agent.report.ReportStore;
import com.supplymind.agent.tool.ToolResult;
import com.supplymind.agent.tool.ToolStatus;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * D6-T02/D6-T04 controlled Agent pipeline: Java decides the intent and the tool chain, executes
 * only read-only tools, builds a versioned EvidencePack (AGENT-EVIDENCE-SCHEMA-V1) with
 * verified evidence refs, then asks the LLM (or the Java template fallback) to explain the
 * deterministic facts. The LLM can never change facts or evidence; malformed or unavailable LLM
 * output always degrades to the template report without breaking the pipeline.
 */
public final class AgentOrchestrator {

    private final ToolExecutor toolExecutor;
    private final LLMService.Port llm;
    private final TemplateFallbackService fallback;
    private final EvidenceRefVerifier evidenceVerifier;
    private final ReportStore reportStore;

    public AgentOrchestrator(
            ToolExecutor toolExecutor,
            LLMService.Port llm,
            TemplateFallbackService fallback,
            EvidenceRefVerifier evidenceVerifier,
            ReportStore reportStore
    ) {
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor");
        this.llm = Objects.requireNonNull(llm, "llm");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.evidenceVerifier = Objects.requireNonNull(evidenceVerifier, "evidenceVerifier");
        this.reportStore = Objects.requireNonNull(reportStore, "reportStore");
    }

    public AgentResult answer(AgentQueryInput input) {
        Objects.requireNonNull(input, "input");
        String requestId = "req-" + UUID.randomUUID().toString().substring(0, 12);
        String mode = input.mode() == null || input.mode().isBlank() ? "FORMAL" : input.mode().toUpperCase();
        List<ToolResult> toolResults = toolExecutor.execute(input, requestId);

        List<EvidencePackV1.ToolExecution> executions = new ArrayList<>();
        List<String> evidenceRefs = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        int index = 0;
        for (ToolResult result : toolResults) {
            executions.add(new EvidencePackV1.ToolExecution(
                    index++, result.toolName(), result.toolVersion(), true,
                    result.inputSummary(), result.result(), result.status(), result.evidenceRefs()));
            for (String ref : result.evidenceRefs()) {
                if (!evidenceRefs.contains(ref)) {
                    evidenceRefs.add(ref);
                }
            }
            notices.addAll(result.notices());
            if (result.status() == ToolStatus.NO_DATA || result.status() == ToolStatus.REJECTED) {
                limitations.add(result.toolName() + ": " + result.inputSummary());
            }
        }
        List<EvidencePackV1.EvidenceRefEntry> verifiedRefs = evidenceVerifier.verifyAll(evidenceRefs);
        List<EvidencePackV1.Fact> facts = buildFacts(toolResults, verifiedRefs);
        for (EvidencePackV1.Fact fact : facts) {
            for (String ref : fact.evidenceRefs()) {
                boolean verified = verifiedRefs.stream()
                        .anyMatch(entry -> entry.ref().equals(ref) && entry.sha256() != null);
                if (!verified) {
                    limitations.add("证据不可用，事实已排除: " + ref);
                    facts = facts.stream().filter(f -> !f.factId().equals(fact.factId())).toList();
                    break;
                }
            }
        }
        EvidencePackV1 evidencePack = new EvidencePackV1(
                "AGENT-EVIDENCE-SCHEMA-V1", "pack-" + requestId, requestId, mode,
                input.question(), OffsetDateTime.now(), scopeOf(input),
                executions, facts, verifiedRefs, List.of(), notices, limitations);

        LLMService.LLMRequest llmRequest = toLlmRequest(requestId, input, facts, evidenceRefs);
        LLMService.LLMResponse llmResponse = llm.analyze(llmRequest);
        boolean degraded = llmResponse.status() != LLMService.LLMStatus.SUCCESS;
        String explanation = degraded
                ? fallback.explain(llmRequest, evidencePack)
                : llmResponse.explanation();

        AgentReportV1 report = new AgentReportV1(
                "AGENT-REPORT-V1", "report-" + requestId, requestId, evidencePack,
                degraded ? "JAVA_TEMPLATE" : "LLM",
                degraded ? null : llmProvider(), degraded ? null : llmModel(),
                degraded, degraded ? llmResponse.failureKind() : null,
                facts.stream().map(fact -> new AgentReportV1.FactSummary(
                        fact.factId(), fact.factType(), fact.value(), fact.businessDate(),
                        fact.periodStart() == null ? fact.businessDate() : fact.periodStart(),
                        fact.validationStatus())).toList(),
                List.of(new AgentReportV1.Claim(
                        "claim-1", explanation, facts.stream()
                                .flatMap(fact -> fact.evidenceRefs().stream()).distinct().toList())),
                List.of(), List.copyOf(limitations), OffsetDateTime.now());
        String reportRef = reportStore.store(report);
        return new AgentResult(evidencePack, llmResponse, reportRef, report);
    }

    private static EvidencePackV1.Scope scopeOf(AgentQueryInput input) {
        return new EvidencePackV1.Scope(
                input.itemId() == null ? List.of() : List.of(input.itemId()),
                input.businessDate(), input.periodStart(), input.periodEnd(), "Asia/Shanghai");
    }
    private static LLMService.LLMRequest toLlmRequest(
            String requestId, AgentQueryInput input, List<EvidencePackV1.Fact> facts,
            List<String> evidenceRefs
    ) {
        List<LLMService.LlmFact> llmFacts = facts.stream()
                .map(fact -> new LLMService.LlmFact(
                        fact.factType(), fact.value(), fact.businessDate(),
                        fact.periodStart() == null ? fact.businessDate() : fact.periodStart() + "~" + fact.periodEnd(),
                        fact.validationStatus(),
                        fact.evidenceRefs().isEmpty() ? null : fact.evidenceRefs().get(0)))
                .toList();
        return new LLMService.LLMRequest(requestId, input.question(),
                input.mode() == null ? "FORMAL" : input.mode(), llmFacts, evidenceRefs);
    }

    private List<EvidencePackV1.Fact> buildFacts(
            List<ToolResult> toolResults, List<EvidencePackV1.EvidenceRefEntry> verifiedRefs
    ) {
        List<EvidencePackV1.Fact> facts = new ArrayList<>();
        int factId = 0;
        for (ToolResult result : toolResults) {
            if (result.status() != ToolStatus.SUCCESS) {
                continue;
            }
            Object rows = result.result().get("rows");
            if (rows instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry instanceof java.util.Map<?, ?> row) {
                        Object itemIdValue = row.get("itemId");
                        String itemId = itemIdValue == null
                                ? String.valueOf(result.result().get("itemId")) : String.valueOf(itemIdValue);
                        String value = row.get("value") == null ? null : String.valueOf(row.get("value"));
                        if (value == null || value.isBlank() || "null".equals(value)) {
                            continue; // missing != zero: no value => no fact
                        }
                        String businessDate = row.get("businessDate") == null
                                ? periodOrNull(row) : String.valueOf(row.get("businessDate"));
                        facts.add(new EvidencePackV1.Fact(
                                "fact-" + (factId++), result.toolName(), itemId, businessDate,
                                row.get("periodStart") == null ? null : String.valueOf(row.get("periodStart")),
                                row.get("periodEnd") == null ? null : String.valueOf(row.get("periodEnd")),
                                value,
                                row.get("unit") == null ? null : String.valueOf(row.get("unit")),
                                row.get("currency") == null ? null : String.valueOf(row.get("currency")),
                                row.get("complete") == null ? null : String.valueOf(row.get("complete")),
                                row.get("validationStatus") == null ? null : String.valueOf(row.get("validationStatus")),
                                row.get("validationVersion") == null ? null : String.valueOf(row.get("validationVersion")),
                                null, null, List.of(), null, null,
                                result.evidenceRefs()));
                    }
                }
            }
        }
        return List.copyOf(facts);
    }

    private static String periodOrNull(java.util.Map<?, ?> row) {
        Object periodStart = row.get("periodStart");
        return periodStart == null ? null : String.valueOf(periodStart);
    }

    private String llmProvider() {
        if (llm instanceof com.supplymind.agent.infrastructure.springai.SpringAiLlmService service) {
            return service.provider();
        }
        return null;
    }

    private String llmModel() {
        if (llm instanceof com.supplymind.agent.infrastructure.springai.SpringAiLlmService service) {
            return service.model();
        }
        return null;
    }

    public record AgentQueryInput(
            String question,
            String itemId,
            String startDate,
            String endDate,
            String grain,
            String periodStart,
            String periodEnd,
            String month,
            String businessDate,
            String mode
    ) {
        public AgentQueryInput {
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("question is required");
            }
        }
    }

    public record AgentResult(
            EvidencePackV1 evidencePack,
            LLMService.LLMResponse llmResponse,
            String reportRef,
            AgentReportV1 report
    ) {
    }
}
