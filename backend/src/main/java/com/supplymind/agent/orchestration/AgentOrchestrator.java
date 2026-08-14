package com.supplymind.agent.orchestration;

import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.agent.application.ModelClaimV1;
import com.supplymind.agent.application.ModelDraftV1;
import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.agent.evidence.EvidenceRefVerifier;
import com.supplymind.agent.evidence.EvidenceStatus;
import com.supplymind.agent.fallback.TemplateFallbackService;
import com.supplymind.agent.llm.LLMService;
import com.supplymind.agent.report.AgentReportV1;
import com.supplymind.agent.report.ReportStore;
import com.supplymind.agent.tool.ToolResult;
import com.supplymind.agent.tool.ToolStatus;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * D6-T02/D6-T04 controlled Agent pipeline (R2 findings M1/M2/M4):
 *
 * - M1: the LLM output is an UNTRUSTED MODEL DRAFT; AgentResponseVerifier checks every model
 *   claim's fact/evidence refs and rejects fabricated numbers or secret injection before the
 *   draft may become formal claims/answer. Any rejection -> deterministic Java fallback.
 * - M2: EvidenceRefs carry full lineage and an explicit status (VERIFIED/MISSING/INVALID/
 *   UNAVAILABLE) with reasonCode; only VERIFIED refs enter the LLM evidence context; missing/
 *   invalid refs are reported in limitations/notices. FORMAL mode excludes demo/synthetic
 *   evidence.
 * - M4: any REJECTED/NO_DATA tool execution invalidates the LLM interaction -> degraded report.
 */
public final class AgentOrchestrator {

    private final ToolExecutor toolExecutor;
    private final LLMService.Port llm;
    private final TemplateFallbackService fallback;
    private final EvidenceRefVerifier evidenceVerifier;
    private final ReportStore reportStore;
    private final AgentResponseVerifier responseVerifier;

    public AgentOrchestrator(
            ToolExecutor toolExecutor,
            LLMService.Port llm,
            TemplateFallbackService fallback,
            EvidenceRefVerifier evidenceVerifier,
            ReportStore reportStore,
            AgentResponseVerifier responseVerifier
    ) {
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor");
        this.llm = Objects.requireNonNull(llm, "llm");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.evidenceVerifier = Objects.requireNonNull(evidenceVerifier, "evidenceVerifier");
        this.reportStore = Objects.requireNonNull(reportStore, "reportStore");
        this.responseVerifier = Objects.requireNonNull(responseVerifier, "responseVerifier");
    }

    public AgentResult answer(AgentQueryInput input) {
        Objects.requireNonNull(input, "input");
        String requestId = "req-" + UUID.randomUUID().toString().substring(0, 12);
        boolean formal = input.mode() == null || input.mode().isBlank()
                || "FORMAL".equalsIgnoreCase(input.mode());
        String mode = formal ? "FORMAL" : "DEMO";

        List<ToolResult> toolResults = toolExecutor.execute(input, requestId);

        // M4: a rejected/invalid tool execution must invalidate the whole LLM interaction.
        boolean toolChainClean = toolResults.stream()
                .allMatch(result -> result.status() == ToolStatus.SUCCESS
                        || result.status() == ToolStatus.NO_DATA);
        if (toolResults.stream().anyMatch(result -> result.status() == ToolStatus.REJECTED)) {
            toolChainClean = false;
        }

        List<EvidencePackV1.ToolExecution> executions = new ArrayList<>();
        List<String> rawEvidenceRefs = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        int index = 0;
        for (ToolResult result : toolResults) {
            executions.add(new EvidencePackV1.ToolExecution(
                    index++, result.toolName(), result.toolVersion(), true,
                    result.inputSummary(), result.result(), result.status(), result.evidenceRefs()));
            for (String ref : result.evidenceRefs()) {
                if (!rawEvidenceRefs.contains(ref)) {
                    rawEvidenceRefs.add(ref);
                }
            }
            notices.addAll(result.notices());
            if (result.status() == ToolStatus.NO_DATA || result.status() == ToolStatus.REJECTED) {
                limitations.add(result.toolName() + ": " + result.inputSummary());
            }
        }
        List<EvidencePackV1.EvidenceRefEntry> verifiedRefs =
                evidenceVerifier.verifyAll(rawEvidenceRefs);

        // M2: FORMAL mode excludes demo/synthetic evidence; only VERIFIED refs are usable.
        List<EvidencePackV1.EvidenceRefEntry> usableRefs = new ArrayList<>();
        for (EvidencePackV1.EvidenceRefEntry entry : verifiedRefs) {
            if (entry.status() != EvidenceStatus.VERIFIED) {
                limitations.add("证据不可用: " + entry.ref() + " (" + entry.status().name()
                        + (entry.reasonCode() == null ? "" : "/" + entry.reasonCode()) + ")");
                continue;
            }
            if (formal && isDemoOrSynthetic(entry)) {
                limitations.add("FORMAL 模式排除 demo/synthetic 证据: " + entry.ref());
                continue;
            }
            usableRefs.add(entry);
        }

        List<EvidencePackV1.Fact> facts = buildFacts(toolResults, usableRefs, formal);
        List<String> llmEvidenceRefs = usableRefs.stream()
                .map(EvidencePackV1.EvidenceRefEntry::ref).toList();

        EvidencePackV1 evidencePack = new EvidencePackV1(
                "AGENT-EVIDENCE-SCHEMA-V1", "pack-" + requestId, requestId, mode,
                input.question(), OffsetDateTime.now(), scopeOf(input),
                executions, facts, usableRefs, List.of(), notices, limitations);

        LLMService.LLMRequest llmRequest = toLlmRequest(requestId, input, facts, llmEvidenceRefs);
        LLMService.LLMResponse llmResponse = llm.analyze(llmRequest);

        boolean degraded;
        String degradeReason;
        String explanation;
        if (!toolChainClean) {
            // M4: rejected tool request -> safe failure -> deterministic Java fallback.
            degraded = true;
            degradeReason = "TOOL_EXECUTION_REJECTED";
            explanation = fallback.explain(llmRequest, evidencePack);
        } else if (llmResponse.status() != LLMService.LLMStatus.SUCCESS) {
            degraded = true;
            degradeReason = llmResponse.failureKind();
            explanation = fallback.explain(llmRequest, evidencePack);
        } else {
            // M1: the LLM output is an untrusted draft; verify before it may become formal.
            ModelDraftV1 draft = ModelDraftV1.untrusted(requestId, llmResponse.explanation());
            AgentResponseVerifier.Verification verification =
                    responseVerifier.verify(draft, evidencePack);
            if (!verification.verified()) {                degraded = true;
                degradeReason = "MODEL_RESPONSE_REJECTED:" + verification.reason();
                explanation = fallback.explain(llmRequest, evidencePack);
            } else {
                degraded = false;
                degradeReason = null;
                explanation = llmResponse.explanation();
            }
        }

        List<AgentReportV1.FactSummary> factSummaries = facts.stream()
                .map(fact -> new AgentReportV1.FactSummary(
                        fact.factId(), fact.factType(), fact.value(), fact.businessDate(),
                        fact.periodStart() == null ? fact.businessDate() : fact.periodStart(),
                        fact.validationStatus()))
                .toList();
        List<AgentReportV1.Claim> claims;
        if (degraded) {
            // M1: JAVA_TEMPLATE claims are produced by deterministic Java from the verified
            // facts - they are trusted, unlike raw model drafts.
            claims = List.of(new AgentReportV1.Claim("claim-1", explanation,
                    facts.stream().flatMap(fact -> fact.evidenceRefs().stream())
                            .distinct().toList()));
        } else {
            claims = List.of(new AgentReportV1.Claim("claim-1", explanation,
                    facts.stream().flatMap(fact -> fact.evidenceRefs().stream())
                            .distinct().toList()));
        }
        AgentReportV1 report = new AgentReportV1(
                "AGENT-REPORT-V1", "report-" + requestId, requestId, evidencePack,
                degraded ? "JAVA_TEMPLATE" : "LLM",
                degraded ? null : llmProvider(), degraded ? null : llmModel(),
                degraded, degradeReason,
                factSummaries, claims, List.of(), List.copyOf(limitations), OffsetDateTime.now());
        String reportRef = reportStore.store(report);
        return new AgentResult(evidencePack, llmResponse, reportRef, report, degraded, degradeReason);
    }

    private static boolean isDemoOrSynthetic(EvidencePackV1.EvidenceRefEntry entry) {
        if (entry.ref().startsWith("warning/")) {
            return true; // warning evidence is TEST/DEMO until EXT-07/08 are confirmed
        }
        return false;
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
            List<ToolResult> toolResults, List<EvidencePackV1.EvidenceRefEntry> usableRefs, boolean formal
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
                    if (entry instanceof Map<?, ?> row) {
                        Object itemIdValue = row.get("itemId");
                        String itemId = itemIdValue == null
                                ? String.valueOf(result.result().get("itemId")) : String.valueOf(itemIdValue);
                        String value = row.get("value") == null ? null : String.valueOf(row.get("value"));
                        if (value == null || value.isBlank() || "null".equals(value)) {
                            continue; // missing != zero: no value => no fact
                        }
                        String businessDate = row.get("businessDate") == null
                                ? periodOrNull(row) : String.valueOf(row.get("businessDate"));
                        List<String> refs = usableRefs(result.evidenceRefs(), usableRefs);
                        if (refs.isEmpty()) {
                            continue; // no verifiable evidence => no fact may be claimed
                        }
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
                                refs));
                    }
                }
            }
        }
        return List.copyOf(facts);
    }

    private static List<String> usableRefs(
            List<String> candidateRefs, List<EvidencePackV1.EvidenceRefEntry> usableRefs
    ) {
        List<String> refs = new ArrayList<>();
        for (String ref : candidateRefs) {
            boolean usable = usableRefs.stream()
                    .anyMatch(entry -> entry.ref().equals(ref));
            if (usable) {
                refs.add(ref);
            }
        }
        return List.copyOf(refs);
    }

    private static String periodOrNull(Map<?, ?> row) {
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
            AgentReportV1 report,
            boolean degraded,
            String degradeReason
    ) {
    }
}
