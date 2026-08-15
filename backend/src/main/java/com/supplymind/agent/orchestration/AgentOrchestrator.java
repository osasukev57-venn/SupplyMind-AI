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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * D6-T02/D6-T04 controlled Agent pipeline (Final Stage Major fixes M1/M2/M4/M5).
 *
 * Two-phase flow:
 *  - Phase A: the model selects tools through the Spring AI ChatClient lifecycle; the adapter
 *    captures the FULL ToolResults of the actually executed tools (M1). The fixed ToolExecutor
 *    is only used as the deterministic FALLBACK query source when the model did not select any
 *    tool or the LLM interaction failed - it is never mixed with the model-selected chain.
 *  - Phase B: the formal EvidencePack is built from the model-selected ToolResults (or the
 *    fallback query), then the LLM is asked to explain ONLY those verified facts
 *    (toolCallingEnabled=false), the draft is verified and the AgentReport is persisted.
 *
 * M2: EvidencePack keeps ALL verification statuses (VERIFIED/MISSING/INVALID/UNAVAILABLE) with
 * their own reasonCode; per-tool lineage is applied per ToolResult; only VERIFIED refs enter the
 * LLM context; every ToolExecution evidenceRef must exist in the top-level EvidencePack.
 * M5: user input is secret-scanned before EvidencePack/LLM/persistence; mode is strict.
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
        String mode = requireMode(input.mode()); // M5: strict FORMAL/DEMO
        // M5: secret-scan the user input before it reaches EvidencePack, the LLM or persistence.
        responseVerifier.requireNoSecret(secretScanText(input));

        // Phase A: the model selects tools (real Spring AI lifecycle). No pre-seeded facts.
        LLMService.LLMRequest phaseA = new LLMService.LLMRequest(
                requestId, input.question(), mode, List.of(), List.of(), true);
        LLMService.LLMResponse phaseAResponse = llm.analyze(phaseA);

        boolean modelSelectedTools = phaseAResponse.status() == LLMService.LLMStatus.SUCCESS
                && !phaseAResponse.toolResults().isEmpty();
        List<ToolResult> evidenceSource;
        boolean fallbackQueryUsed;
        if (modelSelectedTools) {
            // M1: the formal EvidencePack is built from the ToolResults the model actually chose.
            evidenceSource = phaseAResponse.toolResults();
            fallbackQueryUsed = false;
        } else {
            // FALLBACK PATH only: deterministic safe query when no model tool was executed.
            evidenceSource = toolExecutor.execute(input, requestId);
            fallbackQueryUsed = true;
        }

        boolean toolChainClean = evidenceSource.stream()
                .allMatch(result -> result.status() == ToolStatus.SUCCESS
                        || result.status() == ToolStatus.NO_DATA);
        if (evidenceSource.stream().anyMatch(result -> result.status() == ToolStatus.REJECTED)) {
            toolChainClean = false;
        }

        EvidenceBuild build = buildEvidencePack(
                requestId, input, mode, evidenceSource, evidenceVerifier);
        EvidencePackV1 evidencePack = build.evidencePack();
        List<EvidencePackV1.Fact> facts = build.facts();
        List<String> llmEvidenceRefs = build.llmEvidenceRefs();
        List<String> limitations = new ArrayList<>(build.limitations());
        if (fallbackQueryUsed && !modelSelectedTools) {
            limitations.add("fallback: model did not select any tool; deterministic Java query used");
        }

        boolean degraded;
        String degradeReason;
        String explanation;
        List<ModelClaimV1> draftClaims = List.of();
        if (!toolChainClean) {
            degraded = true;
            degradeReason = "TOOL_EXECUTION_REJECTED";
            explanation = fallback.explain(new LLMService.LLMRequest(
                    requestId, input.question(), mode, toLlmFacts(facts), llmEvidenceRefs), evidencePack);
        } else if (phaseAResponse.status() != LLMService.LLMStatus.SUCCESS) {
            degraded = true;
            degradeReason = phaseAResponse.failureKind();
            explanation = fallback.explain(new LLMService.LLMRequest(
                    requestId, input.question(), mode, toLlmFacts(facts), llmEvidenceRefs), evidencePack);
        } else {
            // Phase B: the LLM explains ONLY the verified EvidencePack facts (no further tools).
            LLMService.LLMResponse phaseB = llm.analyze(new LLMService.LLMRequest(
                    requestId, input.question(), mode, toLlmFacts(facts), llmEvidenceRefs, false));
            if (phaseB.status() != LLMService.LLMStatus.SUCCESS) {
                degraded = true;
                degradeReason = phaseB.failureKind();
                explanation = fallback.explain(new LLMService.LLMRequest(
                        requestId, input.question(), mode, toLlmFacts(facts), llmEvidenceRefs), evidencePack);
            } else {
                // M1/M3: the Phase B output is an UNTRUSTED draft; verify before formal use.
                // M3 strict contract: only the strict JSON claims envelope is acceptable; plain
                // free text, malformed JSON, missing/empty/partial claims reject the WHOLE draft
                // (MODEL_RESPONSE_REJECTED:MALFORMED_STRUCTURED_RESPONSE) -> JAVA_TEMPLATE.
                ModelDraftV1 draft = parseStructuredDraft(requestId, phaseB.explanation());
                if (draft == null) {
                    degraded = true;
                    degradeReason = "MODEL_RESPONSE_REJECTED:MALFORMED_STRUCTURED_RESPONSE";
                    explanation = fallback.explain(new LLMService.LLMRequest(
                            requestId, input.question(), mode, toLlmFacts(facts), llmEvidenceRefs), evidencePack);
                } else {
                    draftClaims = draft.claims();
                    AgentResponseVerifier.Verification verification =
                            responseVerifier.verify(draft, evidencePack);
                    if (!verification.verified()) {
                        degraded = true;
                        degradeReason = "MODEL_RESPONSE_REJECTED:" + verification.reason();
                        explanation = fallback.explain(new LLMService.LLMRequest(
                                requestId, input.question(), mode, toLlmFacts(facts), llmEvidenceRefs), evidencePack);
                    } else {
                        degraded = false;
                        degradeReason = null;
                        // M3: the explanation is composed ONLY from the verified claim texts in a
                        // deterministic order - the unverified raw model answer is never reused.
                        explanation = verifiedClaimsText(draft);
                    }
                }
            }
        }

        List<AgentReportV1.FactSummary> factSummaries = facts.stream()
                .map(fact -> new AgentReportV1.FactSummary(
                        fact.factId(), fact.factType(), fact.value(), fact.businessDate(),
                        fact.periodStart() == null ? fact.businessDate() : fact.periodStart(),
                        fact.validationStatus()))
                .toList();
        // M4: claims must never carry empty evidenceRefs; the JAVA_TEMPLATE fallback claim still
        // references the top-level EvidencePack refs (auditable paths) so it stays traceable even
        // when no verified fact value is available.
        List<AgentReportV1.Claim> claims = new ArrayList<>();
        if (!draftClaims.isEmpty() && !degraded) {
            // M3: the persisted AgentReport claims ARE the verified structured claims.
            for (ModelClaimV1 claim : draftClaims) {
                List<String> refs = claimRefsOf(claim, facts);
                if (refs.isEmpty()) {
                    continue;
                }
                claims.add(new AgentReportV1.Claim(claim.claimId(), claim.text(), refs));
            }
        }
        if (claims.isEmpty()) {
            List<String> claimRefs = facts.stream().flatMap(fact -> fact.evidenceRefs().stream())
                    .distinct().toList();
            if (claimRefs.isEmpty()) {
                claimRefs = evidencePack.evidenceRefs().stream()
                        .map(EvidencePackV1.EvidenceRefEntry::ref).distinct().toList();
            }
            if (!claimRefs.isEmpty()) {
                claims.add(new AgentReportV1.Claim("claim-1", explanation, claimRefs));
            }
        }
        AgentReportV1 report = new AgentReportV1(
                "AGENT-REPORT-V1", "report-" + requestId, requestId, evidencePack,
                degraded ? "JAVA_TEMPLATE" : "LLM",
                degraded ? null : llmProvider(), degraded ? null : llmModel(),
                degraded, degradeReason,
                factSummaries, claims, List.of(), List.copyOf(limitations), OffsetDateTime.now());
        String reportRef = reportStore.store(report);
        return new AgentResult(evidencePack, phaseAResponse, reportRef, report, degraded, degradeReason);
    }

    private static EvidenceBuild buildEvidencePack(
            String requestId,
            AgentQueryInput input,
            String mode,
            List<ToolResult> toolResults,
            EvidenceRefVerifier evidenceVerifier
    ) {
        List<EvidencePackV1.ToolExecution> executions = new ArrayList<>();
        List<EvidencePackV1.EvidenceRefEntry> allEntries = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        int index = 0;
        for (ToolResult result : toolResults) {
            String inputSummary = result.inputSummary();
            executions.add(new EvidencePackV1.ToolExecution(
                    index++, result.toolName(), result.toolVersion(), true,
                    inputSummary, result.result(), result.status(), result.evidenceRefs()));
            // M2: per-evidenceRef lineage - evidenceLineageByRef wins, then the tool-level
            // lineage is used as the tool-default; the real file stays the authority for any
            // field it can decode (M4 write-path binding).
            for (String ref : result.evidenceRefs()) {
                ToolResult.Lineage perRef = result.evidenceLineageByRef().get(ref);
                ToolResult.Lineage lineage = perRef != null ? perRef : result.lineage();
                allEntries.add(evidenceVerifier.verifyMerged(ref, lineage));
            }
            notices.addAll(result.notices());
            if (result.status() == ToolStatus.NO_DATA || result.status() == ToolStatus.REJECTED) {
                limitations.add(result.toolName() + ": " + inputSummary);
            }
        }
        List<EvidencePackV1.EvidenceRefEntry> topLevelRefs = new ArrayList<>(allEntries);
        // M2: the EvidencePack carries the FULL four-state structured list (all results incl.
        // MISSING/INVALID/UNAVAILABLE with reasonCode), dedupe by ref keeping the first entry.
        java.util.Map<String, EvidencePackV1.EvidenceRefEntry> byRef = new java.util.LinkedHashMap<>();
        for (EvidencePackV1.EvidenceRefEntry entry : topLevelRefs) {
            byRef.putIfAbsent(entry.ref(), entry);
        }
        List<EvidencePackV1.EvidenceRefEntry> fourStateRefs = new ArrayList<>(byRef.values());
        fourStateRefs.sort(java.util.Comparator.comparing(EvidencePackV1.EvidenceRefEntry::evidenceRefId));
        Set<String> finalRefSet = new LinkedHashSet<>();
        for (EvidencePackV1.EvidenceRefEntry entry : fourStateRefs) {
            finalRefSet.add(entry.ref());
        }
        for (EvidencePackV1.ToolExecution execution : executions) {
            for (String ref : execution.evidenceRefs()) {
                if (!finalRefSet.contains(ref)) {
                    throw new IllegalStateException(
                            "ToolExecution evidenceRef not present in final EvidencePack: " + ref);
                }
            }
        }

        // M2: LLM context is a SEPARATE filtered view: VERIFIED + mode-allowed + lineage-complete.
        List<EvidencePackV1.EvidenceRefEntry> llmUsableRefs = new ArrayList<>();
        for (EvidencePackV1.EvidenceRefEntry entry : fourStateRefs) {
            if (entry.status() != EvidenceStatus.VERIFIED) {
                limitations.add("证据不可用: " + entry.ref() + " (" + entry.status().name()
                        + (entry.reasonCode() == null ? "" : "/" + entry.reasonCode()) + ")");
                continue;
            }
            if ("FORMAL".equals(mode) && isDemoOrSynthetic(entry)) {
                limitations.add("FORMAL 模式排除 demo/synthetic 证据: " + entry.ref());
                continue;
            }
            if ("FORMAL".equals(mode) && !lineageCompleteFor(entry)) {
                limitations.add("证据 lineage 不完整，不可引用: " + entry.ref());
                continue;
            }
            llmUsableRefs.add(entry);
        }
        Set<String> llmUsableRefSet = new LinkedHashSet<>();
        for (EvidencePackV1.EvidenceRefEntry entry : llmUsableRefs) {
            llmUsableRefSet.add(entry.ref());
        }

        List<EvidencePackV1.Fact> facts = buildFacts(toolResults, llmUsableRefSet, mode);
        List<String> llmEvidenceRefs = llmUsableRefs.stream()
                .map(EvidencePackV1.EvidenceRefEntry::ref).toList();
        // M2: the EvidencePack carries the FULL four-state structured list.
        EvidencePackV1 evidencePack = new EvidencePackV1(
                "AGENT-EVIDENCE-SCHEMA-V1", "pack-" + requestId, requestId, mode,
                input.question(), OffsetDateTime.now(), scopeOf(input),
                executions, facts, fourStateRefs, List.of(), notices, limitations);
        return new EvidenceBuild(evidencePack, facts, llmEvidenceRefs, limitations);
    }

    /** M2/M4: applicable lineage fields per refType; RAW does not need aggregate/daily fields. */
    private static boolean lineageCompleteFor(EvidencePackV1.EvidenceRefEntry entry) {
        String type = entry.refType();
        if ("RAW".equals(type)) {
            return entry.runId() != null && entry.rawRef() != null;
        }
        if ("LIFECYCLE".equals(type)) {
            return entry.runId() != null && entry.rawRef() != null
                    && entry.validationVersion() != null;
        }
        if ("DAILY".equals(type)) {
            return entry.businessDate() != null && entry.validationVersion() != null
                    && entry.calculationVersion() != null && entry.calendarVersion() != null
                    && !entry.configVersions().isEmpty();
        }
        if ("AGGREGATE".equals(type)) {
            return entry.periodStart() != null && entry.periodEnd() != null
                    && entry.validationVersion() != null && entry.calculationVersion() != null
                    && entry.calendarVersion() != null && !entry.configVersions().isEmpty();
        }
        if ("CONFIG".equals(type)) {
            return !entry.configVersions().isEmpty();
        }
        if ("WARNING".equals(type)) {
            return true; // warning evidence lineage is the warning ref itself
        }
        return true; // SOURCE and other types: no strict requirement beyond verification
    }

    private static boolean isDemoOrSynthetic(EvidencePackV1.EvidenceRefEntry entry) {
        return entry.ref().startsWith("warning/");
    }

    private static EvidencePackV1.Scope scopeOf(AgentQueryInput input) {
        return new EvidencePackV1.Scope(
                input.itemId() == null ? List.of() : List.of(input.itemId()),
                input.businessDate(), input.periodStart(), input.periodEnd(), "Asia/Shanghai");
    }

    private static List<LLMService.LlmFact> toLlmFacts(List<EvidencePackV1.Fact> facts) {
        return facts.stream()
                .map(fact -> new LLMService.LlmFact(
                        fact.factType(), fact.value(), fact.businessDate(),
                        fact.periodStart() == null ? fact.businessDate() : fact.periodStart() + "~" + fact.periodEnd(),
                        fact.validationStatus(),
                        fact.evidenceRefs().isEmpty() ? null : fact.evidenceRefs().get(0)))
                .toList();
    }

    /**
     * M3 STRICT: the production Phase B path ONLY accepts the strict JSON claims envelope:
     * {"answer": "...", "claims": [{"claimId": "...", "text": "...", "factIds": [...],
     * "evidenceRefs": [...], "sourceNames": [...], "businessDates": [...]}]}.
     * Free text, malformed JSON, missing/empty claims, a claim without any reference or a
     * malformed claim (missing/wrong-typed fields) rejects the WHOLE draft - a bad claim is
     * never skipped in favour of the remaining good ones. Returns null when the response does
     * not satisfy the contract; ModelDraftV1.untrusted(...) is never an acceptable LLM output.
     */
    private static ModelDraftV1 parseStructuredDraft(String requestId, String explanation) {
        String text = explanation == null ? "" : explanation;
        String trimmed = text.trim();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(trimmed);
            com.fasterxml.jackson.databind.JsonNode claimsNode = root.get("claims");
            if (claimsNode == null || !claimsNode.isArray() || claimsNode.isEmpty()) {
                return null;
            }
            List<ModelClaimV1> claims = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode node : claimsNode) {
                if (!node.isObject()) {
                    return null;
                }
                com.fasterxml.jackson.databind.JsonNode claimId = node.get("claimId");
                com.fasterxml.jackson.databind.JsonNode claimText = node.get("text");
                if (claimId == null || !claimId.isTextual() || claimId.asText().isBlank()
                        || claimText == null || !claimText.isTextual()) {
                    return null; // a single malformed claim rejects the whole draft
                }
                List<String> factIds = stringList(node.get("factIds"));
                List<String> refs = stringList(node.get("evidenceRefs"));
                if (factIds.isEmpty() && refs.isEmpty()) {
                    return null; // every formal claim must carry a non-empty reference set
                }
                List<String> sourceNames = stringList(node.get("sourceNames"));
                List<String> businessDates = stringList(node.get("businessDates"));
                claims.add(new ModelClaimV1(
                        claimId.asText(), claimText.asText(), factIds, refs, sourceNames, businessDates));
            }
            if (claims.isEmpty()) {
                return null;
            }
            // rawText carries ONLY the answer field for secret/unknown-reference/number scanning;
            // the persisted explanation is composed from verified claim texts, never this answer.
            com.fasterxml.jackson.databind.JsonNode answer = root.get("answer");
            String rawText = answer != null && answer.isTextual() ? answer.asText() : "";
            return new ModelDraftV1(requestId, rawText, claims);
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            return null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static List<String> stringList(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode element : node) {
            if (!element.isTextual()) {
                throw new IllegalArgumentException("claim field must be a string array");
            }
            values.add(element.asText());
        }
        return values;
    }

    /** M3: the persisted explanation is the deterministic composition of verified claim texts. */
    private static String verifiedClaimsText(ModelDraftV1 draft) {
        StringBuilder builder = new StringBuilder();
        for (ModelClaimV1 claim : draft.claims()) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(claim.text());
        }
        return builder.toString();
    }

    /** M3: the persisted claim references the facts the claim cited (factIds -> fact evidenceRefs). */
    private static List<String> claimRefsOf(ModelClaimV1 claim, List<EvidencePackV1.Fact> facts) {
        List<String> refs = new ArrayList<>();
        Set<String> factIdSet = new LinkedHashSet<>(claim.factIds());
        for (EvidencePackV1.Fact fact : facts) {
            if (factIdSet.contains(fact.factId())) {
                refs.addAll(fact.evidenceRefs());
            }
        }
        refs.addAll(claim.evidenceRefs());
        return refs.stream().distinct().toList();
    }

    private static List<EvidencePackV1.Fact> buildFacts(
            List<ToolResult> toolResults, Set<String> usableRefSet, String mode
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
                        List<String> refs = usableRefs(result.evidenceRefs(), usableRefSet);
                        if (refs.isEmpty()) {
                            continue; // no verifiable evidence => no fact may be claimed
                        }
                        // F4: per-row lineage wins; per-ToolResult lineage is the fallback.
                        ToolResult.Lineage lineage = result.lineage();
                        String calculationVersion = lineage == null ? null : lineage.calculationVersion();
                        String calendarVersion = lineage == null ? null : lineage.calendarVersion();
                        List<String> configVersions = lineage == null ? List.of() : lineage.configVersions();
                        String actualSourceName = lineage == null ? null : lineage.actualSourceName();
                        String sourceFingerprint = lineage == null ? null : lineage.sourceFingerprint();
                        String validationVersion = lineage == null ? null : lineage.validationVersion();
                        if (row.get("calculationVersion") != null) {
                            calculationVersion = String.valueOf(row.get("calculationVersion"));
                        }
                        if (row.get("calendarVersion") != null) {
                            calendarVersion = String.valueOf(row.get("calendarVersion"));
                        }
                        if (row.get("actualSourceName") != null) {
                            actualSourceName = String.valueOf(row.get("actualSourceName"));
                        }
                        if (row.get("validationVersion") != null) {
                            validationVersion = String.valueOf(row.get("validationVersion"));
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
                                validationVersion,
                                calculationVersion, calendarVersion, configVersions,
                                actualSourceName, sourceFingerprint,
                                refs));
                    }
                }
            }
        }
        return List.copyOf(facts);
    }

    private static List<String> usableRefs(List<String> candidateRefs, Set<String> usableRefSet) {
        List<String> refs = new ArrayList<>();
        for (String ref : candidateRefs) {
            if (usableRefSet.contains(ref)) {
                refs.add(ref);
            }
        }
        return List.copyOf(refs);
    }

    private static String periodOrNull(Map<?, ?> row) {
        Object periodStart = row.get("periodStart");
        return periodStart == null ? null : String.valueOf(periodStart);
    }

    private static String requireMode(String mode) {
        if (mode == null || mode.isBlank()) {
            throw new IllegalArgumentException("mode is required (FORMAL or DEMO)");
        }
        if ("FORMAL".equalsIgnoreCase(mode)) {
            return "FORMAL";
        }
        if ("DEMO".equalsIgnoreCase(mode)) {
            return "DEMO";
        }
        throw new IllegalArgumentException("unknown mode: must be FORMAL or DEMO");
    }

    private static String secretScanText(AgentQueryInput input) {
        StringBuilder builder = new StringBuilder();
        if (input.question() != null) {
            builder.append(input.question());
        }
        String[] values = {input.itemId(), input.startDate(), input.endDate(),
                input.grain(), input.periodStart(), input.periodEnd(), input.month(),
                input.businessDate()};
        for (String value : values) {
            if (value != null) {
                builder.append('|').append(value);
            }
        }
        return builder.toString();
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

    private record EvidenceBuild(
            EvidencePackV1 evidencePack,
            List<EvidencePackV1.Fact> facts,
            List<String> llmEvidenceRefs,
            List<String> limitations
    ) {
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
