package com.supplymind.agent.orchestration;

import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.agent.application.ModelClaimV1;
import com.supplymind.agent.application.ModelDraftV1;
import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.agent.evidence.EvidenceRefVerifier;
import com.supplymind.agent.evidence.EvidenceStatus;
import com.supplymind.agent.evidence.RiskProjectionV1;
import com.supplymind.agent.fallback.TemplateFallbackService;
import com.supplymind.agent.infrastructure.springai.WarningExplainToolAdapter;
import com.supplymind.agent.llm.LLMService;
import com.supplymind.agent.report.AgentReportV1;
import com.supplymind.agent.report.ReportStore;
import com.supplymind.agent.tool.ToolResult;
import com.supplymind.agent.tool.ToolStatus;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
 * Fact-first flow:
 *  - Java derives the bounded query, invokes the fixed read-only ToolExecutor and builds a formal
 *    EvidencePack plus standardized facts before any cloud request. If requested facts are absent,
 *    the cloud is not called.
 *  - The LLM receives only the Java-owned fact catalog, selects factIds and organizes language.
 *    Java resolves evidenceRefs from selected facts automatically
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
    private final Clock clock;

    public AgentOrchestrator(
            ToolExecutor toolExecutor,
            LLMService.Port llm,
            TemplateFallbackService fallback,
            EvidenceRefVerifier evidenceVerifier,
            ReportStore reportStore,
            AgentResponseVerifier responseVerifier
    ) {
        this(toolExecutor, llm, fallback, evidenceVerifier, reportStore, responseVerifier,
                Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    public AgentOrchestrator(
            ToolExecutor toolExecutor,
            LLMService.Port llm,
            TemplateFallbackService fallback,
            EvidenceRefVerifier evidenceVerifier,
            ReportStore reportStore,
            AgentResponseVerifier responseVerifier,
            Clock clock
    ) {
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor");
        this.llm = Objects.requireNonNull(llm, "llm");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.evidenceVerifier = Objects.requireNonNull(evidenceVerifier, "evidenceVerifier");
        this.reportStore = Objects.requireNonNull(reportStore, "reportStore");
        this.responseVerifier = Objects.requireNonNull(responseVerifier, "responseVerifier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AgentResult answer(AgentQueryInput input) {
        Objects.requireNonNull(input, "input");
        String requestId = "req-" + UUID.randomUUID().toString().substring(0, 12);
        String mode = requireMode(input.mode()); // M5: strict FORMAL/DEMO
        // M5: secret-scan the user input before it reaches EvidencePack, the LLM or persistence.
        responseVerifier.requireNoSecret(secretScanText(input));

        // Java resolves explicit ISO dates and requested grain before any cloud interaction.
        // The cloud model is never responsible for selecting authoritative data tools.
        AgentQueryInput effectiveInput = deterministicInput(input);
        List<ToolResult> evidenceSource = toolExecutor.execute(effectiveInput, requestId);
        effectiveInput = withResolvedItemId(effectiveInput, evidenceSource);


        boolean toolChainClean = evidenceSource.stream()
                .allMatch(result -> result.status() == ToolStatus.SUCCESS
                        || result.status() == ToolStatus.NO_DATA);
        if (evidenceSource.stream().anyMatch(result -> result.status() == ToolStatus.REJECTED)) {
            toolChainClean = false;
        }

        EvidenceBuild build = buildEvidencePack(
                requestId, effectiveInput, mode, evidenceSource, evidenceVerifier);
        EvidencePackV1 evidencePack = build.evidencePack();
        List<EvidencePackV1.Fact> facts = build.facts();
        List<String> llmEvidenceRefs = build.llmEvidenceRefs();
        List<String> limitations = new ArrayList<>(build.limitations());
        FactReadiness readiness = factReadiness(input.question(), facts);
        if (!toolChainClean) {
            limitations.add("部分只读工具未成功；云端仅可使用已验证标准事实");
        }

        boolean degraded;
        String degradeReason;
        String explanation;
        List<String> draftUsedFactIds = List.of();
        LLMService.LLMResponse cloudResponse = LLMService.LLMResponse.failure(
                LLMService.LLMStatus.REJECTED, "STANDARD_FACTS_NOT_READY");
        if (!readiness.ready()) {
            degraded = true;
            degradeReason = "STANDARD_FACTS_NOT_READY:" + readiness.reason();
            limitations.add("云端分析未调用：" + readiness.reason());
            explanation = fallback.explain(new LLMService.LLMRequest(
                    requestId, input.question(), mode, toLlmFacts(facts), llmEvidenceRefs), evidencePack);
        } else {
            // The sole cloud phase explains ONLY verified standard facts (no tool calling).
            LLMService.LLMResponse phaseB = llm.analyze(new LLMService.LLMRequest(
                    requestId, input.question(), mode, toLlmFacts(facts), llmEvidenceRefs, false));
            cloudResponse = phaseB;
            if (phaseB.status() != LLMService.LLMStatus.SUCCESS) {
                degraded = true;
                degradeReason = phaseB.failureKind();
                explanation = fallback.explain(new LLMService.LLMRequest(
                        requestId, input.question(), mode, toLlmFacts(facts), llmEvidenceRefs), evidencePack);
            } else {
                // The cloud returns only polished answer + selected StandardFactPack ids. Java`n                // verifies every selected id and all stated numbers/dates/sources/conclusions,`n                // then re-attaches evidence. Malformed or unsupported output fails closed.
                ModelDraftV1 draft = parseStructuredDraft(requestId, phaseB.explanation());
                if (draft == null) {
                    degraded = true;
                    degradeReason = "MODEL_RESPONSE_REJECTED:MALFORMED_STRUCTURED_RESPONSE";
                    explanation = fallback.explain(new LLMService.LLMRequest(
                            requestId, input.question(), mode, toLlmFacts(facts), llmEvidenceRefs), evidencePack);
                } else {
                    draftUsedFactIds = draft.usedFactIds();
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
                        // The answer is safe to display only after validation against the Java-owned`n                        // facts selected by usedFactIds.
                        explanation = draft.rawText();
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
        if (!draftUsedFactIds.isEmpty() && !degraded) {
            // The cloud supplies language + selected factIds only. Java resolves every evidence
            // reference from the selected StandardFactPack entries and persists one verified claim.
            List<String> refs = claimRefsOf(draftUsedFactIds, facts);
            if (!refs.isEmpty()) {
                claims.add(new AgentReportV1.Claim("claim-1", explanation, refs));
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
        RiskProjectionV1 riskProjection = buildRiskProjection(evidenceSource, evidencePack, mode);
        return new AgentResult(evidencePack, cloudResponse, reportRef, report, degraded, degradeReason,
                riskProjection);
    }

    /**
     * M3: projects the risk view ONLY from the real warning.explain ToolResult structured row
     * contract (warningId/currentValue/baselineValue/threshold/riskLevel/dataStatus/period).
     * The projection is allowed only when the row's evidenceRef is VERIFIED and lineage-
     * complete in the final EvidencePack; MISSING/INVALID/UNAVAILABLE evidence never produces a
     * risk view. Tool identity is the structured toolName equality - never string contains.
     */
    private static RiskProjectionV1 buildRiskProjection(
            List<ToolResult> toolResults, EvidencePackV1 evidencePack, String mode
    ) {
        Set<String> modeAllowedRefs = evidencePack.evidenceRefs().stream()
                .filter(entry -> entry.status() == EvidenceStatus.VERIFIED
                        && lineageCompleteFor(entry))
                .filter(entry -> !("FORMAL".equals(mode) && isDemoOrSynthetic(entry)))
                .map(EvidencePackV1.EvidenceRefEntry::ref)
                .collect(java.util.stream.Collectors.toSet());
        for (ToolResult result : toolResults) {
            if (result.status() != ToolStatus.SUCCESS) {
                continue;
            }
            if (!WarningExplainToolAdapter.TOOL_NAME.equals(result.toolName())) {
                continue; // structured tool identity - never a contains() guess
            }
            Object rows = result.result().get("rows");
            List<?> list;
            if (rows instanceof List<?> rowsList) {
                list = rowsList;
            } else {
                // M3: warning.explain rows live under the structured "warnings" key.
                Object warningRows = result.result().get("warnings");
                if (!(warningRows instanceof List<?> warningList)) {
                    continue;
                }
                list = warningList;
            }
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> row)) {
                    continue;
                }
                Object riskLevel = row.get("riskLevel");
                Object currentValue = row.get("currentValue");
                Object warningId = row.get("warningId");
                Object itemId = row.get("itemId");
                if (riskLevel == null || currentValue == null || warningId == null || itemId == null) {
                    continue; // incomplete structured row: no projection
                }
                List<String> rowRefs = warningRowRefs(row, modeAllowedRefs);
                if (rowRefs.isEmpty()) {
                    continue; // no VERIFIED, mode-allowed, row-owned evidence: no risk view
                }
                return new RiskProjectionV1(
                        String.valueOf(warningId),
                        String.valueOf(itemId),
                        row.get("grain") == null ? null : String.valueOf(row.get("grain")),
                        row.get("periodStart") == null ? null : String.valueOf(row.get("periodStart")),
                        row.get("periodEnd") == null ? null : String.valueOf(row.get("periodEnd")),
                        String.valueOf(riskLevel),
                        String.valueOf(currentValue),
                        row.get("baselineValue") == null ? null : String.valueOf(row.get("baselineValue")),
                        row.get("threshold") == null ? null : String.valueOf(row.get("threshold")),
                        row.get("dataStatus") == null ? null : String.valueOf(row.get("dataStatus")),
                        Boolean.TRUE.equals(row.get("demoRule")),
                        rowRefs);
            }
        }
        return null;
    }

    private static List<String> warningRowRefs(Map<?, ?> row, Set<String> modeAllowedRefs) {
        Object value = row.get("evidenceRefs");
        if (!(value instanceof List<?> refs) || refs.isEmpty()) {
            return List.of();
        }
        return refs.stream().map(String::valueOf)
                .filter(modeAllowedRefs::contains).distinct().toList();
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
                // containsKey is intentional: explicit ambiguous lineage suppresses fallback.
                ToolResult.Lineage lineage = result.evidenceLineageByRef().containsKey(ref)
                        ? result.evidenceLineageByRef().get(ref)
                        : result.lineage();
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
    private AgentQueryInput deterministicInput(AgentQueryInput input) {
        String startDate = input.startDate();
        String endDate = input.endDate();
        List<String> dates = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\d{4}-\\d{2}-\\d{2}")
                .matcher(input.question());
        while (matcher.find()) {
            dates.add(matcher.group());
        }
        if ((startDate == null || startDate.isBlank()) && !dates.isEmpty()) {
            startDate = dates.get(0);
        }
        if ((endDate == null || endDate.isBlank()) && !dates.isEmpty()) {
            endDate = dates.size() > 1 ? dates.get(1) : dates.get(0);
        }
        if ((startDate == null || startDate.isBlank())
                && (endDate == null || endDate.isBlank())
                && (input.periodStart() == null || input.periodStart().isBlank())
                && (input.month() == null || input.month().isBlank())
                && (input.businessDate() == null || input.businessDate().isBlank())) {
            java.time.LocalDate today = java.time.LocalDate.now(
                    clock.withZone(ZoneId.of("Asia/Shanghai")));
            startDate = java.time.YearMonth.from(today).atDay(1).toString();
            endDate = today.toString();
        }

        String grain = input.grain();
        String periodStart = input.periodStart();
        String periodEnd = input.periodEnd();
        String month = input.month();
        String question = input.question().toLowerCase(java.util.Locale.ROOT);
        boolean asksMonthly = question.contains("月均") || question.contains("月度平均")
                || question.contains("monthly average");
        if (asksMonthly && startDate != null) {
            try {
                java.time.YearMonth target = java.time.YearMonth.from(
                        java.time.LocalDate.parse(startDate));
                if (grain == null || grain.isBlank()) {
                    grain = "month";
                }
                if (periodStart == null || periodStart.isBlank()) {
                    periodStart = target.atDay(1).toString();
                }
                if (periodEnd == null || periodEnd.isBlank()) {
                    periodEnd = target.atEndOfMonth().toString();
                }
            } catch (java.time.DateTimeException ignored) {
                // ToolArguments performs the authoritative fail-closed validation later.
            }
        }
        if ((month == null || month.isBlank()) && question.contains("预警")
                && startDate != null && startDate.length() >= 7) {
            month = startDate.substring(0, 7);
        }
        return new AgentQueryInput(
                input.question(), input.itemId(), startDate, endDate, grain,
                periodStart, periodEnd, month, input.businessDate(), input.mode());
    }

    private static AgentQueryInput withResolvedItemId(
            AgentQueryInput input, List<ToolResult> toolResults
    ) {
        if (input.itemId() != null && !input.itemId().isBlank()) {
            return input;
        }
        String resolved = toolResults.stream()
                .filter(result -> "series.resolve".equals(result.toolName()))
                .filter(result -> result.status() == ToolStatus.SUCCESS)
                .map(result -> result.result().get("itemId"))
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .findFirst().orElse(null);
        if (resolved == null || resolved.isBlank()) {
            return input;
        }
        return new AgentQueryInput(
                input.question(), resolved, input.startDate(), input.endDate(), input.grain(),
                input.periodStart(), input.periodEnd(), input.month(), input.businessDate(), input.mode());
    }

    private static FactReadiness factReadiness(
            String questionText, List<EvidencePackV1.Fact> facts
    ) {
        if (facts.isEmpty()) {
            return new FactReadiness(false, "没有可引用的已验证标准事实");
        }
        String question = questionText == null
                ? "" : questionText.toLowerCase(java.util.Locale.ROOT);
        if ((question.contains("最新") || question.contains("latest"))
                && facts.stream().noneMatch(fact -> "history.latest".equals(fact.factType()))) {
            return new FactReadiness(false, "缺少最新已发布值事实");
        }
        if ((question.contains("月均") || question.contains("月度平均")
                || question.contains("monthly average"))
                && facts.stream().noneMatch(fact -> "period.metrics.avg".equals(fact.factType()))) {
            return new FactReadiness(false, "缺少持久化月度平均事实");
        }
        if ((question.contains("数据质量") || question.contains("完整")
                || question.contains("quality"))
                && facts.stream().noneMatch(fact -> "quality.rowCount".equals(fact.factType()))) {
            return new FactReadiness(false, "缺少质量统计事实");
        }
        if ((question.contains("来源") || question.contains("source"))
                && facts.stream().noneMatch(fact -> fact.actualSourceName() != null
                && !fact.actualSourceName().isBlank())) {
            return new FactReadiness(false, "缺少可追溯实际来源事实");
        }
        return new FactReadiness(true, "READY");
    }

    private record FactReadiness(boolean ready, String reason) {
    }

    private static String standardStatement(EvidencePackV1.Fact fact) {
        return switch (fact.factType()) {
            case "history.query" -> "published daily value";
            case "history.latest" -> "latest published value";
            case "period.metrics.avg" -> "persisted period average";
            case "quality.rowCount" -> "published row count";
            case "quality.expectedCount" -> "expected observation count";
            case "quality.missingCount" -> "missing observation count";
            case "quality.completeRowCount" -> "complete row count";
            case "quality.completeness" -> "Java-computed completeness percentage";
            case "warning.explain" -> "verified warning current value";
            case "warning.baselineValue" -> "verified warning baseline value";
            case "warning.thresholdPercent" -> "Java-evaluated warning threshold percentage";
            case "warning.riskLevel" -> "Java-evaluated warning risk level";
            case "warning.id" -> "immutable warning identifier";
            case "warning.acknowledged" -> "verified warning acknowledgement status";
            case "warning.acknowledgedAt" -> "verified warning acknowledgement time";
            case "warning.disposition" -> "verified warning disposition note";
            case "history.trend.direction" -> "Java-computed trend direction";
            case "history.trend.change" -> "Java-computed start-to-end change";
            default -> fact.factType();
        };
    }
    private static List<LLMService.LlmFact> toLlmFacts(List<EvidencePackV1.Fact> facts) {
        return facts.stream()
                .map(fact -> new LLMService.LlmFact(
                        fact.factId(), standardStatement(fact), fact.itemId(), fact.value(), fact.unit(), fact.currency(),
                        fact.businessDate(),
                        fact.periodStart() == null ? fact.businessDate() : fact.periodStart() + "~" + fact.periodEnd(),
                        fact.validationStatus(), fact.actualSourceName(),
                        fact.evidenceRefs().isEmpty() ? null : fact.evidenceRefs().get(0)))
                .toList();
    }

    /**
     * Production cloud contract: {"answer":"...","usedFactIds":["fact-..."]}.
     * The model never supplies claims, evidence paths, hashes, dates or source side channels;
     * Java verifies the answer against the selected StandardFactPack entries and re-attaches refs.
     */
    private static ModelDraftV1 parseStructuredDraft(String requestId, String explanation) {
        String trimmed = explanation == null ? "" : explanation.trim();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(trimmed);
            if (!root.isObject() || root.has("claims") || root.has("evidenceRefs")) {
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode answer = root.get("answer");
            List<String> usedFactIds = stringList(root.get("usedFactIds"));
            if (answer == null || !answer.isTextual() || answer.asText().isBlank()
                    || usedFactIds.isEmpty()) {
                return null;
            }
            return new ModelDraftV1(requestId, answer.asText(), usedFactIds, List.of());
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException malformed) {
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
                throw new IllegalArgumentException("usedFactIds must be a string array");
            }
            values.add(element.asText());
        }
        return values;
    }

    /** Java resolves evidence refs exclusively from the selected factIds. */
    private static List<String> claimRefsOf(
            List<String> usedFactIds, List<EvidencePackV1.Fact> facts
    ) {
        Set<String> selected = new LinkedHashSet<>(usedFactIds);
        return facts.stream().filter(fact -> selected.contains(fact.factId()))
                .flatMap(fact -> fact.evidenceRefs().stream()).distinct().toList();
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
            if (!(rows instanceof List<?>)) {
                rows = result.result().get("warnings");
            }
            if (rows instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry instanceof Map<?, ?> row) {
                        Object itemIdValue = row.get("itemId");
                        String itemId = itemIdValue == null
                                ? String.valueOf(result.result().get("itemId")) : String.valueOf(itemIdValue);
                        String factType = result.toolName();
                        String value = row.get("value") == null ? null : String.valueOf(row.get("value"));
                        if (value == null || value.isBlank() || "null".equals(value)) {
                            // M3: warning.explain rows use the structured currentValue field.
                            // Persisted aggregate rows use avg/min/max rather than a generic
                            // value field. The formal monthly average is projected explicitly so
                            // the LLM never has to recompute it from daily rows.
                            if ("period.metrics".equals(result.toolName()) && row.get("avg") != null) {
                                value = String.valueOf(row.get("avg"));
                                factType = "period.metrics.avg";
                            }
                            if (WarningExplainToolAdapter.TOOL_NAME.equals(result.toolName())) {
                                Object currentValue = row.get("currentValue");
                                if (currentValue != null && !String.valueOf(currentValue).isBlank()) {
                                    value = String.valueOf(currentValue);
                                }
                            }
                            if (value == null || value.isBlank() || "null".equals(value)) {
                                continue; // missing != zero: no value => no fact
                            }
                        }
                        String businessDate = row.get("businessDate") == null
                                ? periodOrNull(row) : String.valueOf(row.get("businessDate"));
                        // M2 R4: each fact uses ONLY the refs of the row it really came from
                        // (row-level evidenceRefs when the tool provides them, never the whole
                        // ToolResult set) - raw-A must never back a fact that only raw-B produced.
                        List<String> rowRefs = rowRefsOf(row, result, usableRefSet);
                        if (rowRefs.isEmpty()) {
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
                        if (row.get("configVersions") instanceof List<?> rowConfigVersions) {
                            configVersions = rowConfigVersions.stream()
                                    .map(String::valueOf).distinct()
                                    .sorted(java.util.Comparator.comparingLong(Long::parseLong))
                                    .toList();
                        }
                        if (row.get("actualSourceName") != null) {
                            actualSourceName = String.valueOf(row.get("actualSourceName"));
                        }
                        if (row.get("validationVersion") != null) {
                            validationVersion = String.valueOf(row.get("validationVersion"));
                        }
                        if (row.get("sourceFingerprint") != null) {
                            sourceFingerprint = String.valueOf(row.get("sourceFingerprint"));
                        }
                        facts.add(new EvidencePackV1.Fact(
                                "fact-" + (factId++), factType, itemId, businessDate,
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
                                rowRefs));
                        if (WarningExplainToolAdapter.TOOL_NAME.equals(result.toolName())) {
                            EvidencePackV1.Fact warningFact = facts.get(facts.size() - 1);
                            addDerivedFact(facts, "fact-" + (factId++), "warning.baselineValue",
                                    row.get("baselineValue"), warningFact.unit(), warningFact);
                            addDerivedFact(facts, "fact-" + (factId++), "warning.thresholdPercent",
                                    row.get("thresholdPercent"), "%", warningFact);
                            addDerivedFact(facts, "fact-" + (factId++), "warning.riskLevel",
                                    row.get("riskLevel"), "risk", warningFact);
                            addDerivedFact(facts, "fact-" + (factId++), "warning.id",
                                    row.get("warningId"), "id", warningFact);
                            addDerivedFact(facts, "fact-" + (factId++), "warning.acknowledged",
                                    row.get("acknowledgedStatus"), "status", warningFact);
                            addDerivedFact(facts, "fact-" + (factId++), "warning.acknowledgedAt",
                                    row.get("acknowledgedAt"), "datetime", warningFact);
                            addDerivedFact(facts, "fact-" + (factId++), "warning.disposition",
                                    row.get("dispositionNote"), "text", warningFact);
                        }
                    }
                }
            }
        }
        List<EvidencePackV1.Fact> dailyFacts = facts.stream()
                .filter(fact -> "history.query".equals(fact.factType()))
                .toList();
        if (!dailyFacts.isEmpty()) {
            EvidencePackV1.Fact latest = dailyFacts.stream()
                    .filter(fact -> fact.businessDate() != null)
                    .max(java.util.Comparator.comparing(EvidencePackV1.Fact::businessDate))
                    .orElse(dailyFacts.get(dailyFacts.size() - 1));
            facts.add(copyFact("fact-" + (factId++), "history.latest", latest));
            EvidencePackV1.Fact earliest = dailyFacts.stream()
                    .filter(fact -> fact.businessDate() != null)
                    .min(java.util.Comparator.comparing(EvidencePackV1.Fact::businessDate))
                    .orElse(dailyFacts.get(0));
            if (dailyFacts.size() >= 2 && earliest.value() != null && latest.value() != null) {
                try {
                    java.math.BigDecimal startValue = new java.math.BigDecimal(earliest.value());
                    java.math.BigDecimal endValue = new java.math.BigDecimal(latest.value());
                    String direction = endValue.compareTo(startValue) > 0 ? "UPWARD"
                            : endValue.compareTo(startValue) < 0 ? "DOWNWARD" : "FLAT";
                    List<String> trendRefs = dailyFacts.stream()
                            .flatMap(fact -> fact.evidenceRefs().stream()).distinct().toList();
                    facts.add(standardFact("fact-" + (factId++), "history.trend.direction",
                            latest, earliest.businessDate(), latest.businessDate(),
                            direction, "direction", trendRefs));
                    facts.add(standardFact("fact-" + (factId++), "history.trend.change",
                            latest, earliest.businessDate(), latest.businessDate(),
                            endValue.subtract(startValue).toPlainString(), latest.unit(), trendRefs));
                } catch (NumberFormatException ignored) {
                    // Non-numeric series still retain their published facts; no trend is invented.
                }
            }

            List<String> dailyRefs = dailyFacts.stream()
                    .flatMap(fact -> fact.evidenceRefs().stream()).distinct().toList();
            String periodStart = dailyFacts.stream().map(EvidencePackV1.Fact::businessDate)
                    .filter(Objects::nonNull).min(String::compareTo).orElse(null);
            String periodEnd = dailyFacts.stream().map(EvidencePackV1.Fact::businessDate)
                    .filter(Objects::nonNull).max(String::compareTo).orElse(null);
            for (ToolResult result : toolResults) {
                if (!"quality.inspect".equals(result.toolName())
                        || result.status() != ToolStatus.SUCCESS || dailyRefs.isEmpty()) {
                    continue;
                }
                String itemId = String.valueOf(result.result().get("itemId"));
                int rowCount = integerOf(result.result().get("rowCount"));
                int expectedCount = integerOf(result.result().get("expectedCountTotal"));
                int missingCount = integerOf(result.result().get("missingCountTotal"));
                int completeRowCount = integerOf(result.result().get("completeRowCount"));
                facts.add(standardFact("fact-" + (factId++), "quality.rowCount",
                        latest, periodStart, periodEnd, String.valueOf(rowCount), "count", dailyRefs));
                facts.add(standardFact("fact-" + (factId++), "quality.expectedCount",
                        latest, periodStart, periodEnd, String.valueOf(expectedCount), "count", dailyRefs));
                facts.add(standardFact("fact-" + (factId++), "quality.missingCount",
                        latest, periodStart, periodEnd, String.valueOf(missingCount), "count", dailyRefs));
                facts.add(standardFact("fact-" + (factId++), "quality.completeRowCount",
                        latest, periodStart, periodEnd, String.valueOf(completeRowCount), "count", dailyRefs));
                if (expectedCount > 0) {
                    java.math.BigDecimal completeness = java.math.BigDecimal
                            .valueOf(Math.max(expectedCount - missingCount, 0))
                            .multiply(java.math.BigDecimal.valueOf(100))
                            .divide(java.math.BigDecimal.valueOf(expectedCount), 12,
                                    java.math.RoundingMode.HALF_UP)
                            .stripTrailingZeros();
                    facts.add(standardFact("fact-" + (factId++), "quality.completeness",
                            latest, periodStart, periodEnd,
                            completeness.toPlainString() + "%", "%", dailyRefs));
                }
            }
        }
        return List.copyOf(facts);
    }
    private static void addDerivedFact(
            List<EvidencePackV1.Fact> facts, String factId, String factType,
            Object rawValue, String unit, EvidencePackV1.Fact source
    ) {
        if (rawValue == null || String.valueOf(rawValue).isBlank()
                || "null".equals(String.valueOf(rawValue))) {
            return;
        }
        facts.add(new EvidencePackV1.Fact(
                factId, factType, source.itemId(), source.businessDate(),
                source.periodStart(), source.periodEnd(), String.valueOf(rawValue), unit,
                source.currency(), source.qualityStatus(), source.validationStatus(),
                source.validationVersion(), source.calculationVersion(), source.calendarVersion(),
                source.configVersions(), source.actualSourceName(), source.sourceFingerprint(),
                source.evidenceRefs()));
    }
    private static EvidencePackV1.Fact copyFact(
            String factId, String factType, EvidencePackV1.Fact source
    ) {
        return new EvidencePackV1.Fact(
                factId, factType, source.itemId(), source.businessDate(),
                source.periodStart(), source.periodEnd(), source.value(), source.unit(),
                source.currency(), source.qualityStatus(), source.validationStatus(),
                source.validationVersion(), source.calculationVersion(), source.calendarVersion(),
                source.configVersions(), source.actualSourceName(), source.sourceFingerprint(),
                source.evidenceRefs());
    }

    private static EvidencePackV1.Fact standardFact(
            String factId, String factType, EvidencePackV1.Fact source,
            String periodStart, String periodEnd,
            String value, String unit, List<String> evidenceRefs
    ) {
        return new EvidencePackV1.Fact(
                factId, factType, source.itemId(), null, periodStart, periodEnd, value, unit,
                source.currency(), "VERIFIED", source.validationStatus(),
                source.validationVersion(), source.calculationVersion(), source.calendarVersion(),
                source.configVersions(), source.actualSourceName(), source.sourceFingerprint(), evidenceRefs);
    }

    private static int integerOf(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
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

    /**
     * M2 R4: the row's OWN evidence refs when the tool exposes them (history.query rows carry
     * their inputRefs); tools without row-level refs fall back to their ToolResult refs - the
     * whole-tool set is never assigned to a row that declares its own refs.
     */
    private static List<String> rowRefsOf(Map<?, ?> row, ToolResult result, Set<String> usableRefSet) {
        Object rowRefsValue = row.get("evidenceRefs");
        if (rowRefsValue instanceof List<?> rowRefs && !rowRefs.isEmpty()) {
            List<String> refs = new ArrayList<>();
            for (Object ref : rowRefs) {
                String refText = String.valueOf(ref);
                if (usableRefSet.contains(refText)) {
                    refs.add(refText);
                }
            }
            return List.copyOf(refs);
        }
        return usableRefs(result.evidenceRefs(), usableRefSet);
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
            String degradeReason,
            // M3: structured risk projection from the real warning.explain ToolResult rows.
            RiskProjectionV1 riskProjection
    ) {
        /** Backward-compatible constructor without the risk projection. */
        public AgentResult(
                EvidencePackV1 evidencePack,
                LLMService.LLMResponse llmResponse,
                String reportRef,
                AgentReportV1 report,
                boolean degraded,
                String degradeReason
        ) {
            this(evidencePack, llmResponse, reportRef, report, degraded, degradeReason, null);
        }
    }
}
