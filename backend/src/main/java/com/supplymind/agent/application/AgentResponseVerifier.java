package com.supplymind.agent.application;

import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.agent.evidence.EvidenceStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M1/M3/M5 AgentResponseVerifier: the LLM output is an UNTRUSTED MODEL DRAFT. Verification rules:
 *
 * Production contract: Java builds the StandardFactPack; the cloud may return only polished
 * {@code answer + usedFactIds}. Every selected id must exist in the current pack, every number,
 * date and source in the answer must be supported by those selected facts, and controlled
 * trend/risk/acknowledgement wording must not contradict them. Java attaches evidence refs only
 * after verification. Historical structured-claim verification remains for frozen attack tests.
 *
 * M5: `requireNoSecret` scans USER INPUT (question/args) before EvidencePack/LLM/persistence.
 *
 * Verification outcome is binary: ACCEPTED or REJECTED (with a stable reason) - partial
 * acceptance is forbidden.
 */
public final class AgentResponseVerifier {

    /**
     * M3: full-token business numbers. Scientific notation comes FIRST so "7.15e6" is one token
     * (never truncated to "7.15" plus a dangling "e6"); thousands groups require at least one
     * separator; the plain decimal form is the tail. "12%" and "12" stay DIFFERENT tokens - a
     * percentage only matches a fact whose value is itself a percentage (unit-aware).
     */
    private static final Pattern NUMBER_TOKEN = Pattern.compile(
            "-?\\d+(?:\\.\\d+)?[eE][+-]?\\d+%?"
                    + "|-?\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?%?"
                    + "|-?\\d+(?:\\.\\d+)?%?");
    private static final Pattern DATE_TOKEN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern FACT_REF = Pattern.compile("fact-[A-Za-z0-9._-]+");
    private static final Pattern SOURCE_MARKER = Pattern.compile(
            "(?iu)(?:\\bsource\\b|来源|来自)");
    private static final Pattern EVIDENCE_REF = Pattern
            .compile("(raw|processed|staging|warning|config)/[A-Za-z0-9._/-]+\\.(json|csv)");

    private final Set<String> configuredSecrets;

    public AgentResponseVerifier(List<String> configuredSecrets) {
        this.configuredSecrets = configuredSecrets == null
                ? Set.of() : Set.copyOf(configuredSecrets);
    }

    /** M5: fail closed when user input carries a secret; safe before any persistence. */
    public void requireNoSecret(String text) {
        String hit = findSecret(text);
        if (hit != null) {
            throw new IllegalArgumentException("input rejected: credential-like content detected");
        }
    }

    public Verification verify(ModelDraftV1 draft, EvidencePackV1 evidencePack) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(evidencePack, "evidencePack");

        // M3 R4: the secret guard scans EVERY persistable field of the structured response -
        // answer, claimId, claim.text, factIds, evidenceRefs, sourceNames, businessDates.
        if (secretInAnyPersistableField(draft)) {
            return Verification.rejected("SECRET_INJECTION");
        }
        Set<String> knownFactIds = new LinkedHashSet<>();
        Set<String> knownVerifiedEvidenceRefs = new LinkedHashSet<>();
        List<EvidenceFact> facts = new ArrayList<>();
        for (EvidencePackV1.Fact fact : evidencePack.facts()) {
            knownFactIds.add(fact.factId());
            knownVerifiedEvidenceRefs.addAll(fact.evidenceRefs());
            facts.add(new EvidenceFact(fact.factId(), fact.factType(), fact.value(), fact.itemId(), fact.unit(), fact.currency(),
                    fact.evidenceRefs(), fact.businessDate(), fact.periodStart(), fact.periodEnd(),
                    fact.actualSourceName()));
        }
        for (EvidencePackV1.EvidenceRefEntry entry : evidencePack.evidenceRefs()) {
            if (entry.status() == EvidenceStatus.VERIFIED) {
                knownVerifiedEvidenceRefs.add(entry.ref());
            }
        }

        // Current production cloud contract: answer + usedFactIds only. Java owns the
        // StandardFactPack and re-attaches evidence after this verification succeeds.
        if (!draft.usedFactIds().isEmpty()) {
            return verifyUsedFactsAnswer(draft, facts, knownFactIds);
        }
        // M3: unknown references anywhere in the draft reject the whole draft.
        String unknownRef = findUnknownReference(draft.rawText(), knownFactIds, knownVerifiedEvidenceRefs);
        if (unknownRef != null) {
            return Verification.rejected("UNKNOWN_" + (unknownRef.startsWith("fact-")
                    ? "FACT_REFERENCE" : "EVIDENCE_REFERENCE"));
        }
        // M3: structured claims - each claim's references must support EVERY number the claim
        // states (per-number binding: for every number in claim, a referenced fact supports it;
        // the global fact set never helps a claim through).
        for (ModelClaimV1 claim : draft.claims()) {
            for (String factId : claim.factIds()) {
                if (!knownFactIds.contains(factId)) {
                    return Verification.rejected("UNKNOWN_FACT_REFERENCE");
                }
            }
            for (String ref : claim.evidenceRefs()) {
                if (!knownVerifiedEvidenceRefs.contains(ref)) {
                    return Verification.rejected("UNKNOWN_EVIDENCE_REFERENCE");
                }
            }
            if (!everyNumberSupportedByClaimFacts(claim, facts)) {
                return Verification.rejected("UNSUPPORTED_CLAIM_REFERENCE");
            }
            if (!claimDateAndSourceSupported(claim, facts)) {
                return Verification.rejected("UNSUPPORTED_CLAIM_REFERENCE");
            }
            if (!claimSourceDeclarationsClosed(claim, facts)) {
                return Verification.rejected("UNSUPPORTED_CLAIM_REFERENCE");
            }
        }

        // M3: every business number in the free text must match a verified fact value.
        List<String> numbers = extractBusinessNumbers(draft.rawText());
        for (String number : numbers) {
            if (!matchesAnyFactValue(number, facts)) {
                return Verification.rejected("FABRICATED_NUMBER");
            }
        }
        // F2 micro-fix: a draft that restates a REAL formal business number must still cite at
        // least one valid factId or VERIFIED evidenceRef from the current request.
        if (!numbers.isEmpty() && !hasAnyValidReference(draft, knownFactIds, knownVerifiedEvidenceRefs)) {
            return Verification.rejected("MISSING_REQUIRED_REFERENCE");
        }
        return Verification.accepted();
    }

    /**
     * Verifies prose whose business literals were rendered by Java from an already closed fact
     * placeholder set. Unlike cloud-authored free text, these literals must not be reclassified by
     * regex heuristics: doing so can split immutable identifiers (for example a warningId) into
     * apparent numbers and reject Java's own authoritative output. The cloud shell was checked
     * before rendering; this stage keeps secret, reference, evidence and controlled-semantic gates.
     */
    public Verification verifyJavaRenderedFactTemplate(
            ModelDraftV1 draft, EvidencePackV1 evidencePack
    ) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(evidencePack, "evidencePack");
        if (secretInAnyPersistableField(draft)) {
            return Verification.rejected("SECRET_INJECTION");
        }
        List<EvidenceFact> facts = evidencePack.facts().stream()
                .map(fact -> new EvidenceFact(
                        fact.factId(), fact.factType(), fact.value(), fact.itemId(), fact.unit(),
                        fact.currency(), fact.evidenceRefs(), fact.businessDate(),
                        fact.periodStart(), fact.periodEnd(), fact.actualSourceName()))
                .toList();
        Set<String> knownFactIds = new LinkedHashSet<>();
        facts.forEach(fact -> knownFactIds.add(fact.factId()));
        if (draft.rawText().isBlank()) {
            return Verification.rejected("EMPTY_ANSWER");
        }
        LinkedHashSet<String> selectedIds = new LinkedHashSet<>();
        for (String factId : draft.usedFactIds()) {
            if (factId == null || factId.isBlank() || !knownFactIds.contains(factId)) {
                return Verification.rejected("UNKNOWN_FACT_REFERENCE");
            }
            if (!selectedIds.add(factId)) {
                return Verification.rejected("DUPLICATE_FACT_REFERENCE");
            }
        }
        if (selectedIds.isEmpty()
                || FACT_REF.matcher(draft.rawText()).find()
                || EVIDENCE_REF.matcher(draft.rawText()).find()) {
            return Verification.rejected("MODEL_REFERENCE_LEAKAGE");
        }
        List<EvidenceFact> selected = facts.stream()
                .filter(fact -> selectedIds.contains(fact.factId()))
                .toList();
        if (selected.size() != selectedIds.size()
                || selected.stream().anyMatch(fact -> fact.evidenceRefs() == null
                || fact.evidenceRefs().isEmpty())) {
            return Verification.rejected("UNSUPPORTED_CLAIM_REFERENCE");
        }
        if (contradictsControlledSemantics(draft.rawText(), selected)) {
            return Verification.rejected("CONTRADICTED_STANDARD_FACT");
        }
        if (!hasSemanticAnchor(draft.rawText(), selected)) {
            return Verification.rejected("UNSUPPORTED_CLAIM_REFERENCE");
        }
        return Verification.accepted();
    }

    private Verification verifyUsedFactsAnswer(
            ModelDraftV1 draft, List<EvidenceFact> facts, Set<String> knownFactIds
    ) {
        if (draft.rawText().isBlank()) {
            return Verification.rejected("EMPTY_ANSWER");
        }
        LinkedHashSet<String> selectedIds = new LinkedHashSet<>();
        for (String factId : draft.usedFactIds()) {
            if (factId == null || factId.isBlank() || !knownFactIds.contains(factId)) {
                return Verification.rejected("UNKNOWN_FACT_REFERENCE");
            }
            if (!selectedIds.add(factId)) {
                return Verification.rejected("DUPLICATE_FACT_REFERENCE");
            }
        }
        if (FACT_REF.matcher(draft.rawText()).find() || EVIDENCE_REF.matcher(draft.rawText()).find()) {
            return Verification.rejected("MODEL_REFERENCE_LEAKAGE");
        }
        List<EvidenceFact> selected = facts.stream()
                .filter(fact -> selectedIds.contains(fact.factId()))
                .toList();
        List<String> sourceNames = selected.stream().map(EvidenceFact::actualSourceName)
                .filter(Objects::nonNull).filter(value -> !value.isBlank()).distinct().toList();
        ModelClaimV1 synthetic = new ModelClaimV1(
                "cloud-answer", draft.rawText(), List.copyOf(selectedIds), List.of(),
                sourceNames, List.of());
        if (!everyNumberSupportedByClaimFacts(synthetic, selected)
                || !claimDateAndSourceSupported(synthetic, selected)
                || !claimSourceDeclarationsClosed(synthetic, selected)) {
            return Verification.rejected("UNSUPPORTED_CLAIM_REFERENCE");
        }
        if (contradictsControlledSemantics(draft.rawText(), selected)) {
            return Verification.rejected("CONTRADICTED_STANDARD_FACT");
        }
        if (!hasSemanticAnchor(draft.rawText(), selected)) {
            return Verification.rejected("UNSUPPORTED_CLAIM_REFERENCE");
        }
        return Verification.accepted();
    }

    /** At least one selected Java fact must be visibly represented in the polished answer. */
    private static boolean hasSemanticAnchor(String answer, List<EvidenceFact> selected) {
        String lower = answer.toLowerCase(java.util.Locale.ROOT);
        for (EvidenceFact fact : selected) {
            for (String literal : new String[]{
                    fact.value(), fact.itemId(), fact.unit(), fact.currency(), fact.businessDate(),
                    fact.actualSourceName()}) {
                if (literal != null && !literal.isBlank() && answer.contains(literal)) {
                    return true;
                }
            }
            String value = fact.value() == null ? "" : fact.value().toUpperCase(java.util.Locale.ROOT);
            if ("history.trend.direction".equals(fact.factType())) {
                if (("UPWARD".equals(value) && (answer.contains("上升") || answer.contains("上涨")))
                        || ("DOWNWARD".equals(value) && (answer.contains("下降") || answer.contains("下跌")))
                        || ("FLAT".equals(value) && (answer.contains("持平") || answer.contains("平稳")))) {
                    return true;
                }
            }
            if ("warning.riskLevel".equals(fact.factType())) {
                if (lower.contains(value.toLowerCase(java.util.Locale.ROOT))
                        || ("HIGH".equals(value) && (answer.contains("高风险") || answer.contains("高等级") || answer.contains("严重")))
                        || ("MEDIUM".equals(value) && (answer.contains("中风险") || answer.contains("中等风险")))
                        || ("LOW".equals(value) && (answer.contains("低风险") || answer.contains("较低风险")))) {
                    return true;
                }
            }
            if ("warning.acknowledged".equals(fact.factType())
                    && (("ACKNOWLEDGED".equals(value) && (answer.contains("已确认") || answer.contains("已处理")))
                    || ("PENDING".equals(value) && (answer.contains("待处理") || answer.contains("未确认"))))) {
                return true;
            }
        }
        return false;
    }

    /** Reject polished prose that states the opposite of a selected Java-controlled conclusion. */
    private static boolean contradictsControlledSemantics(String answer, List<EvidenceFact> selected) {
        for (EvidenceFact fact : selected) {
            String value = fact.value() == null ? "" : fact.value().toUpperCase(java.util.Locale.ROOT);
            if ("history.trend.direction".equals(fact.factType())) {
                if (("UPWARD".equals(value) && containsAny(answer, "下降", "下跌", "走低"))
                        || ("DOWNWARD".equals(value) && containsAny(answer, "上升", "上涨", "走高"))
                        || ("FLAT".equals(value) && containsAny(answer, "上升", "上涨", "下降", "下跌"))) {
                    return true;
                }
            }
            if ("warning.acknowledged".equals(fact.factType())) {
                if (("ACKNOWLEDGED".equals(value) && containsAny(answer, "待处理", "未确认", "未处理"))
                        || ("PENDING".equals(value) && containsAny(answer, "已确认", "已处理"))) {
                    return true;
                }
            }
            if ("warning.riskLevel".equals(fact.factType())) {
                if (("HIGH".equals(value) && containsAny(answer, "低风险", "中风险"))
                        || ("MEDIUM".equals(value) && containsAny(answer, "高风险", "低风险"))
                        || ("LOW".equals(value) && containsAny(answer, "高风险", "中风险"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    /** M3 R4: any secret in ANY persistable structured field rejects the whole draft. */
    private boolean secretInAnyPersistableField(ModelDraftV1 draft) {
        if (findSecret(draft.rawText()) != null
                || findSecret(String.join(" ", draft.usedFactIds())) != null) {
            return true;
        }
        for (ModelClaimV1 claim : draft.claims()) {
            if (findSecret(claim.claimId()) != null
                    || findSecret(claim.text()) != null
                    || findSecret(String.join(" ", claim.factIds())) != null
                    || findSecret(String.join(" ", claim.evidenceRefs())) != null
                    || findSecret(String.join(" ", claim.sourceNames())) != null
                    || findSecret(String.join(" ", claim.businessDates())) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * M3: PER-NUMBER binding - every business number token in the claim text must be supported
     * by at least one fact the claim references (by factId or evidenceRef). A number that only
     * exists in a GLOBAL, unreferenced fact never helps this claim pass. Position-aware: number
     * tokens lying INSIDE an ISO date token span are not business numbers at all.
     */
    private static boolean everyNumberSupportedByClaimFacts(ModelClaimV1 claim, List<EvidenceFact> facts) {
        List<EvidenceFact> referenced = referencedFacts(claim, facts);
        List<String> claimNumbers = extractClaimBusinessNumbers(claim.text(), referenced);
        for (String number : claimNumbers) {
            if (!matchesAnyFactValueInFacts(number, referenced)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Numbers embedded in an exact, cited metadata literal are identifiers/units rather than
     * calculated business values (for example AZ91D or CNY/1 USD). Only exact occurrences from
     * the claim's referenced facts are exempt; the same number elsewhere still needs a fact value.
     */
    private static List<String> extractClaimBusinessNumbers(String text, List<EvidenceFact> referenced) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<int[]> nonValueSpans = new ArrayList<>();
        Matcher dateMatcher = DATE_TOKEN.matcher(text);
        while (dateMatcher.find()) {
            nonValueSpans.add(new int[]{dateMatcher.start(), dateMatcher.end()});
        }
        for (EvidenceFact fact : referenced) {
            addLiteralSpans(text, fact.itemId(), nonValueSpans);
            addLiteralSpans(text, fact.unit(), nonValueSpans);
            addLiteralSpans(text, fact.currency(), nonValueSpans);
            addLiteralSpans(text, fact.actualSourceName(), nonValueSpans);
        }
        List<String> numbers = new ArrayList<>();
        Matcher matcher = NUMBER_TOKEN.matcher(text);
        while (matcher.find()) {
            if (!insideAnyDateSpan(matcher.start(), matcher.end(), nonValueSpans)) {
                numbers.add(matcher.group());
            }
        }
        return List.copyOf(numbers);
    }

    private static void addLiteralSpans(String text, String literal, List<int[]> spans) {
        if (literal == null || literal.isBlank()) {
            return;
        }
        int from = 0;
        while (from < text.length()) {
            int start = text.indexOf(literal, from);
            if (start < 0) {
                return;
            }
            spans.add(new int[]{start, start + literal.length()});
            from = start + literal.length();
        }
    }

    /** M3: the facts this claim references (by factId or evidenceRef) - the ONLY support set. */
    private static List<EvidenceFact> referencedFacts(ModelClaimV1 claim, List<EvidenceFact> facts) {
        List<EvidenceFact> referenced = new ArrayList<>();
        for (EvidenceFact fact : facts) {
            boolean byFactId = claim.factIds() != null && claim.factIds().contains(fact.factId());
            boolean byRef = claim.evidenceRefs() != null && fact.evidenceRefs() != null
                    && claim.evidenceRefs().stream().anyMatch(fact.evidenceRefs()::contains);
            if (byFactId || byRef) {
                referenced.add(fact);
            }
        }
        return referenced;
    }

    /** M3: every date appearing in the text must be covered by a referenced fact's date/period. */
    private static List<String> extractDates(String text) {
        List<String> dates = new ArrayList<>();
        if (text == null) {
            return dates;
        }
        Matcher dateMatcher = DATE_TOKEN.matcher(text);
        while (dateMatcher.find()) {
            dates.add(dateMatcher.group());
        }
        return dates;
    }

    private static boolean matchesAnyFactValueInFacts(String number, List<EvidenceFact> facts) {
        for (EvidenceFact fact : facts) {
            if (matchesAnyFactValueInList(fact.value(), List.of(number))) {
                return true;
            }
        }
        return false;
    }

    /**
     * M3: a structured claim's dates, periods and source declarations must be supported by the
     * facts it references - every full ISO date in the claim text, every explicit
     * businessDates[] entry and every explicit sourceNames[] entry must be backed by a
     * referenced fact. Source declarations are NEVER accepted from unverifiable free text.
     */
    private static boolean claimDateAndSourceSupported(ModelClaimV1 claim, List<EvidenceFact> facts) {
        List<EvidenceFact> referenced = referencedFacts(claim, facts);
        List<String> textDates = extractDates(claim.text());
        for (String date : textDates) {
            if (!referenced.stream().anyMatch(fact -> supportsDate(fact, date))) {
                return false;
            }
        }
        for (String date : claim.businessDates()) {
            if (date == null || !referenced.stream().anyMatch(fact -> supportsDate(fact, date))) {
                return false;
            }
        }
        for (String source : claim.sourceNames()) {
            boolean backed = source != null && referenced.stream().anyMatch(
                    fact -> source.equals(fact.actualSourceName()));
            if (!backed) {
                return false;
            }
        }
        return true;
    }

    /**
     * M3 R4 SOURCE CLOSURE: a source declaration in claim.text must be declared in
     * sourceNames[] and backed by a referenced fact. A known source name appearing in the text
     * with an empty/missing sourceNames[] rejects; a source declaration naming anything NOT in
     * the known source set cannot be reliably verified and fails closed.
     */
    private static boolean claimSourceDeclarationsClosed(ModelClaimV1 claim, List<EvidenceFact> facts) {
        Set<String> knownSourceNames = new LinkedHashSet<>();
        for (EvidenceFact fact : facts) {
            if (fact.actualSourceName() != null && !fact.actualSourceName().isBlank()) {
                knownSourceNames.add(fact.actualSourceName());
            }
        }
        String text = claim.text() == null ? "" : claim.text();
        // Every KNOWN source name mentioned in the text must be explicitly declared.
        for (String known : knownSourceNames) {
            if (text.contains(known) && !claim.sourceNames().contains(known)) {
                return false;
            }
        }
        // Every source marker must be followed by one of this claim's explicitly declared and
        // already fact-backed sourceNames. Matching the declared string itself supports Chinese
        // and multi-word names and fails closed when the declaration cannot be parsed reliably.
        Matcher matcher = SOURCE_MARKER.matcher(text);
        int coveredUntil = -1;
        while (matcher.find()) {
            if (matcher.start() < coveredUntil) {
                continue; // marker word inside an already verified multi-word source name
            }
            int declarationStart = skipSourceConnector(text, matcher.end());
            String matchedSource = null;
            for (String source : claim.sourceNames()) {
                if (source != null && !source.isBlank()
                        && text.startsWith(source, declarationStart)
                        && sourceDeclarationEndsHere(
                                text, declarationStart + source.length())) {
                    matchedSource = source;
                    break;
                }
            }
            if (matchedSource == null) {
                return false;
            }
            coveredUntil = declarationStart + matchedSource.length();
        }
        return true;
    }

    /**
     * A declared source must end exactly at the authoritative source name. Whitespace alone is
     * not a boundary because it would allow an authoritative prefix such as "PBOC" to validate
     * "PBOC Fake Branch". The structured response contract therefore requires the source name
     * to be followed by end-of-text or explicit punctuation; prose can continue after that
     * delimiter. This is deliberately fail-closed.
     */
    private static boolean sourceDeclarationEndsHere(String text, int sourceEnd) {
        int index = sourceEnd;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        if (index == text.length()) {
            return true;
        }
        return ",.;:!?，。；：！？、()（）[]【】".indexOf(text.charAt(index)) >= 0;
    }

    private static int skipSourceConnector(String text, int start) {
        int index = start;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (Character.isWhitespace(current) || current == ':' || current == '：') {
                index++;
                continue;
            }
            break;
        }
        for (String connector : List.of("from", "为", "于", "是", "自")) {
            if (text.regionMatches(true, index, connector, 0, connector.length())) {
                index += connector.length();
                while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                    index++;
                }
                break;
            }
        }
        return index;
    }

    private static boolean supportsDate(EvidenceFact fact, String date) {
        if (date.equals(fact.businessDate())) {
            return true;
        }
        if (fact.periodStart() != null && fact.periodEnd() != null) {
            try {
                java.time.LocalDate when = java.time.LocalDate.parse(date);
                java.time.LocalDate start = java.time.LocalDate.parse(fact.periodStart());
                java.time.LocalDate end = java.time.LocalDate.parse(fact.periodEnd());
                return !when.isBefore(start) && !when.isAfter(end);
            } catch (java.time.format.DateTimeParseException ignored) {
                return false;
            }
        }
        return false;
    }

    private static boolean matchesAnyFactValue(String number, List<EvidenceFact> facts) {
        for (EvidenceFact fact : facts) {
            if (matchesAnyFactValueInList(fact.value(), List.of(number))) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAnyFactValueInList(String factValue, List<String> numbers) {
        if (factValue == null || factValue.isBlank()) {
            return false;
        }
        String normalizedFact = normalizeNumber(factValue);
        for (String number : numbers) {
            if (normalizeNumber(number).equals(normalizedFact)) {
                return true;
            }
        }
        return false;
    }

    /**
     * M3: normalize 1,234.56 -> 1234.56; 1.5e3 -> 1500; keep - sign. A trailing % is PART OF the
     * token: "12%" only matches a fact whose value is itself "12%" (percentage is never silently
     * equivalent to the plain number - it only matches when the fact's unit IS a percentage).
     */
    private static String normalizeNumber(String token) {
        String value = token.trim();
        boolean negative = value.startsWith("-");
        if (negative) {
            value = value.substring(1);
        }
        String percent = "";
        if (value.endsWith("%")) {
            percent = "%";
            value = value.substring(0, value.length() - 1);
        }
        value = value.replace(",", "");
        try {
            BigDecimal decimal = new BigDecimal(value);
            value = decimal.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ignored) {
            // keep the raw token; exact-string matching will still reject unknown forms
        }
        return (negative ? "-" : "") + value + percent;
    }

    /** M3: extract business numbers from free text (integer/one-decimal/multi/negative/percent/thousands/scientific). */
    static List<String> extractNumbers(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> numbers = new ArrayList<>();
        Matcher matcher = NUMBER_TOKEN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            numbers.add(token);
        }
        return List.copyOf(numbers);
    }

    /**
     * M3 R4 POSITION-AWARE: business numbers are only the tokens that are NOT part of an ISO
     * date token span ("2026-08-10" is a date - its 2026/08/10 fragments are never business
     * numbers); a standalone "20" next to the date IS a business number and must be supported
     * by a referenced fact value. The old date.contains(number) exemption is forbidden.
     */
    static List<String> extractBusinessNumbers(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<int[]> dateSpans = new ArrayList<>();
        Matcher dateMatcher = DATE_TOKEN.matcher(text);
        while (dateMatcher.find()) {
            dateSpans.add(new int[]{dateMatcher.start(), dateMatcher.end()});
        }
        List<String> numbers = new ArrayList<>();
        Matcher matcher = NUMBER_TOKEN.matcher(text);
        while (matcher.find()) {
            if (insideAnyDateSpan(matcher.start(), matcher.end(), dateSpans)) {
                continue;
            }
            numbers.add(matcher.group());
        }
        return List.copyOf(numbers);
    }

    private static boolean insideAnyDateSpan(int start, int end, List<int[]> dateSpans) {
        for (int[] span : dateSpans) {
            if (start >= span[0] && end <= span[1]) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnyValidReference(
            ModelDraftV1 draft, Set<String> knownFactIds, Set<String> knownEvidenceRefs
    ) {
        for (ModelClaimV1 claim : draft.claims()) {
            for (String factId : claim.factIds()) {
                if (knownFactIds.contains(factId)) {
                    return true;
                }
            }
            for (String ref : claim.evidenceRefs()) {
                if (knownEvidenceRefs.contains(ref)) {
                    return true;
                }
            }
        }
        String text = draft.rawText() == null ? "" : draft.rawText();
        Matcher factMatcher = FACT_REF.matcher(text);
        while (factMatcher.find()) {
            if (knownFactIds.contains(factMatcher.group())) {
                return true;
            }
        }
        Matcher refMatcher = EVIDENCE_REF.matcher(text);
        while (refMatcher.find()) {
            if (knownEvidenceRefs.contains(refMatcher.group())) {
                return true;
            }
        }
        return false;
    }

    private static String findUnknownReference(
            String rawText, Set<String> knownFactIds, Set<String> knownEvidenceRefs
    ) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        Matcher factMatcher = FACT_REF.matcher(rawText);
        while (factMatcher.find()) {
            String token = factMatcher.group();
            if (!knownFactIds.contains(token)) {
                return token;
            }
        }
        Matcher refMatcher = EVIDENCE_REF.matcher(rawText);
        while (refMatcher.find()) {
            String token = refMatcher.group();
            if (!knownEvidenceRefs.contains(token)) {
                return token;
            }
        }
        return null;
    }

    private String findSecret(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        for (String secret : configuredSecrets) {
            if (secret != null && secret.length() >= 6 && rawText.contains(secret)) {
                return "configured_secret";
            }
        }
        String lower = rawText.toLowerCase();
        if (lower.contains("authorization: bearer ") || rawText.contains("Bearer ")
                || lower.contains("bearer ")) {
            return "bearer_token_pattern";
        }
        if (lower.contains("sk-") && (lower.contains("api") || lower.contains("key"))) {
            return "api_key_pattern";
        }
        if (lower.contains("api_key") || lower.contains("apikey")
                || lower.contains("x-api-key")) {
            return "api_key_field";
        }
        if (lower.contains("cookie: ") || lower.contains("password=")
                || lower.contains("passwd=") || lower.contains("token=")) {
            return "credential_field";
        }
        return null;
    }

    private record EvidenceFact(
            String factId, String factType, String value, String itemId, String unit, String currency,
            List<String> evidenceRefs, String businessDate, String periodStart, String periodEnd,
            String actualSourceName
    ) {
    }

    public record Verification(boolean verified, String reason) {
        public static Verification accepted() {
            return new Verification(true, null);
        }

        public static Verification rejected(String reason) {
            return new Verification(false, reason);
        }
    }
}
