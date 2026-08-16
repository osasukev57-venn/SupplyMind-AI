package com.supplymind.agent;

import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.agent.evidence.EvidenceRefVerifier;
import com.supplymind.agent.evidence.EvidenceStatus;
import com.supplymind.agent.infrastructure.springai.HistoryQueryToolAdapter;
import com.supplymind.agent.llm.LLMService;
import com.supplymind.agent.orchestration.AgentOrchestrator;
import com.supplymind.agent.support.Day6R2Fixture;
import com.supplymind.agent.tool.ToolResult;
import com.supplymind.agent.tool.ToolStatus;
import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.CanonicalJsonV1;
import com.supplymind.foundation.model.DailyInputRefV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.FileDigest;
import com.supplymind.foundation.storage.ManifestFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DAY6 Round4 M2: row-to-evidenceRef binding. Each fact uses ONLY the refs of the row it came
 * from (raw-A never backs a raw-B fact); per-ref lineage is row-specific (own fingerprint,
 * permanent tombstone on heterogeneity, A->B->A never re-adds); configVersions is an ORDERED
 * LIST - full-list equality keeps VERIFIED with the list preserved, list difference fails
 * closed as AMBIGUOUS_FILE_LINEAGE.
 */
class Day6FinalStageRowToRefConfigTest {

    @TempDir
    Path temp;

    @Test
    void twoRowsFromDifferentRawsNeverCrossSupportFacts() throws Exception {
        MultiRowFixture fixture = MultiRowFixture.create(temp, "cross-raw", "100.00000000", "200.00000000");
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> {
                    String valueB = request.facts().size() > 1
                            ? request.facts().get(1).value() : "200.00000000";
                    return LLMService.LLMResponse.success(
                            "{\"answer\":\"ok\",\"claims\":[{\"claimId\":\"c1\","
                                    + "\"text\":\"值为 " + valueB + "\","
                                    + "\"factIds\":[\"fact-0\"],\"evidenceRefs\":[]}]}");
                }, new AgentResponseVerifier(List.of())).answer(fixture.rangeQuery());

        assertTrue(result.degraded(),
                "value B backed only by raw-B must never pass while the claim references raw-A's fact");
        assertTrue(result.degradeReason().contains("UNSUPPORTED_CLAIM_REFERENCE"),
                "reason=" + result.degradeReason());
        assertEquals("JAVA_TEMPLATE", result.report().generatedBy());
    }

    @Test
    void factEvidenceRefsExactlyEqualTheirOwnRowRefs() throws Exception {
        MultiRowFixture fixture = MultiRowFixture.create(temp, "row-refs", "100.00000000", "200.00000000");
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                request -> LLMService.LLMResponse.success(
                        "{\"answer\":\"ok\",\"claims\":[{\"claimId\":\"c1\",\"text\":\"ok\","
                                + "\"factIds\":[\"fact-0\"],\"evidenceRefs\":[]}]}"),
                new AgentResponseVerifier(List.of())).answer(fixture.rangeQuery());

        assertTrue(result.evidencePack().facts().size() >= 2, "two rows must produce two facts");
        for (EvidencePackV1.Fact fact : result.evidencePack().facts()) {
            assertFalse(fact.evidenceRefs().isEmpty(), "every fact must carry its row refs");
        }
        EvidencePackV1.Fact first = result.evidencePack().facts().get(0);
        EvidencePackV1.Fact second = result.evidencePack().facts().get(1);
        assertEquals(List.of(fixture.rawARef()), first.evidenceRefs(),
                "fact of row-A must carry EXACTLY raw-A");
        assertEquals(List.of(fixture.rawBRef()), second.evidenceRefs(),
                "fact of row-B must carry EXACTLY raw-B");
        assertEquals("Row Source A", first.actualSourceName());
        assertEquals(List.of("1"), first.configVersions());
        assertEquals("Row Source B", second.actualSourceName());
        assertEquals(List.of("2", "3"), second.configVersions());
        assertEquals(CanonicalJsonV1.sha256LowerHex(CanonicalJsonV1.sourceIdentity(
                ProviderType.OFFICIAL_WEB, "Row Source A", AccessMethod.PUBLIC_OFFICIAL_HTML)), first.sourceFingerprint());
        assertEquals(CanonicalJsonV1.sha256LowerHex(CanonicalJsonV1.sourceIdentity(
                ProviderType.OFFICIAL_WEB, "Row Source B", AccessMethod.PUBLIC_OFFICIAL_HTML)), second.sourceFingerprint());
    }

    @Test
    void heterogeneousRefLineageStaysTombstonedEvenInABaOrder() throws Exception {
        Day6R2Fixture base = Day6R2Fixture.create(temp, "tombstone");
        String rawRef = base.rawRef();
        String dailyRef = DataPaths.dailyRef(Day6R2Fixture.ITEM, YearMonth.of(2026, 8));
        List<DailyRecordV1> rows = List.of(
                dailyRow("2026-08-08", rawRef, "pboc-validation-v1", Day6R2Fixture.RUN_ID),
                dailyRow("2026-08-09", rawRef, "pboc-validation-v2", Day6R2Fixture.RUN_ID),
                dailyRow("2026-08-10", rawRef, "pboc-validation-v1", Day6R2Fixture.RUN_ID));
        byte[] data = CsvV1Codec.encodeDaily(rows);
        ManifestV1 manifest = ManifestFactory.csv(dailyRef, data, 3, "2026-08-08", "2026-08-10",
                List.of(Day6R2Fixture.RUN_ID), Day6R2Fixture.AT);
        Files.write(base.root().resolveDataRef(dailyRef), data);
        Files.write(base.root().resolveDataRef(DataPaths.manifestRef(dailyRef)),
                JsonV1Codec.encodeFile(manifest));

        HistoryQueryToolAdapter adapter = base.historyQuery();
        ToolResult result = adapter.historyQuery(Day6R2Fixture.ITEM, "2026-08-08", "2026-08-10", "req-tomb");
        assertEquals(ToolStatus.SUCCESS, result.status());
        assertTrue(result.evidenceLineageByRef().containsKey(rawRef),
                "an explicit map entry must distinguish ambiguous from missing/fallback");
        assertEquals(ToolResult.Lineage.ambiguous(), result.evidenceLineageByRef().get(rawRef),
                "A->B->A must retain the explicit ambiguous marker");
        assertEquals(EvidenceStatus.UNAVAILABLE,
                new EvidenceRefVerifier(base.root()).verifyWithAuthoritativeLineage(dailyRef).status(),
                "the file-level lineage must also fail closed as ambiguous");

        AgentOrchestrator.AgentResult pipeline = base.orchestrator(
                request -> LLMService.LLMResponse.success(
                        "{\"answer\":\"ok\",\"claims\":[{\"claimId\":\"c1\",\"text\":\"ok\","
                                + "\"factIds\":[\"fact-0\"],\"evidenceRefs\":[]}]}"),
                new AgentResponseVerifier(List.of())).answer(new AgentOrchestrator.AgentQueryInput(
                        "analyse heterogeneous ref", Day6R2Fixture.ITEM,
                        "2026-08-08", "2026-08-10", null, null, null, null, null, "FORMAL"));
        EvidencePackV1.EvidenceRefEntry finalRaw = pipeline.evidencePack().evidenceRefs().stream()
                .filter(entry -> entry.ref().equals(rawRef)).findFirst().orElseThrow();
        assertNull(finalRaw.validationVersion(),
                "final EvidencePack must not refill ambiguous ref with first-row validationVersion");
        assertNull(finalRaw.calculationVersion(),
                "final EvidencePack must not refill ambiguous ref with first-row calculationVersion");
        assertNull(finalRaw.calendarVersion(),
                "final EvidencePack must not refill ambiguous ref with first-row calendarVersion");
    }

    @Test
    void rowSourceFingerprintsAreRowSpecific() throws Exception {
        MultiRowFixture fixture = MultiRowFixture.create(temp, "fingerprints", "100.00000000", "200.00000000");
        ToolResult result = fixture.historyQuery();
        assertEquals(ToolStatus.SUCCESS, result.status());

        ToolResult.Lineage rawALineage = result.evidenceLineageByRef().get(fixture.rawARef());
        ToolResult.Lineage rawBLineage = result.evidenceLineageByRef().get(fixture.rawBRef());
        assertNotNull(rawALineage, "raw-A must carry its own lineage");
        assertNotNull(rawBLineage, "raw-B must carry its own lineage");
        String fingerprintA = CanonicalJsonV1.sha256LowerHex(CanonicalJsonV1.sourceIdentity(
                ProviderType.OFFICIAL_WEB, "Row Source A", AccessMethod.PUBLIC_OFFICIAL_HTML));
        String fingerprintB = CanonicalJsonV1.sha256LowerHex(CanonicalJsonV1.sourceIdentity(
                ProviderType.OFFICIAL_WEB, "Row Source B", AccessMethod.PUBLIC_OFFICIAL_HTML));
        assertEquals(fingerprintA, rawALineage.sourceFingerprint(),
                "raw-A must use the fingerprint of ITS OWN row source");
        assertEquals(fingerprintB, rawBLineage.sourceFingerprint(),
                "raw-B must use the fingerprint of ITS OWN row source");
        assertFalse(fingerprintA.equals(fingerprintB));
    }

    @Test
    void configVersionsOrderedListEqualAcrossRowsStaysVerifiedWithListPreserved() throws Exception {
        Day6R2Fixture base = Day6R2Fixture.create(temp, "config-list-ok");
        String dailyRef = DataPaths.dailyRef(Day6R2Fixture.ITEM, YearMonth.of(2026, 8));
        List<DailyRecordV1> rows = List.of(
                dailyRow("2026-08-08", base.rawRef(), Day6R2Fixture.RUN_ID, List.of(2, 1)),
                dailyRow("2026-08-09", base.rawRef(), Day6R2Fixture.RUN_ID, List.of(1, 2)));
        byte[] data = CsvV1Codec.encodeDaily(rows);
        ManifestV1 manifest = ManifestFactory.csv(dailyRef, data, 2, "2026-08-08", "2026-08-09",
                List.of(Day6R2Fixture.RUN_ID), Day6R2Fixture.AT);
        Files.write(base.root().resolveDataRef(dailyRef), data);
        Files.write(base.root().resolveDataRef(DataPaths.manifestRef(dailyRef)),
                JsonV1Codec.encodeFile(manifest));

        EvidencePackV1.EvidenceRefEntry entry =
                new EvidenceRefVerifier(base.root()).verifyWithAuthoritativeLineage(dailyRef);
        assertEquals(EvidenceStatus.VERIFIED, entry.status());
        assertEquals(List.of("1", "2"), entry.configVersions(),
                "the FULL normalized ordered list must be preserved - [1,2] is legal, not ambiguous");
    }

    @Test
    void configVersionsListDifferenceIsAmbiguous() throws Exception {
        Day6R2Fixture base = Day6R2Fixture.create(temp, "config-list-mismatch");
        String dailyRef = DataPaths.dailyRef(Day6R2Fixture.ITEM, YearMonth.of(2026, 8));
        List<DailyRecordV1> rows = List.of(
                dailyRow("2026-08-08", base.rawRef(), Day6R2Fixture.RUN_ID, List.of(1, 2)),
                dailyRow("2026-08-09", base.rawRef(), Day6R2Fixture.RUN_ID, List.of(1, 3)));
        byte[] data = CsvV1Codec.encodeDaily(rows);
        ManifestV1 manifest = ManifestFactory.csv(dailyRef, data, 2, "2026-08-08", "2026-08-09",
                List.of(Day6R2Fixture.RUN_ID), Day6R2Fixture.AT);
        Files.write(base.root().resolveDataRef(dailyRef), data);
        Files.write(base.root().resolveDataRef(DataPaths.manifestRef(dailyRef)),
                JsonV1Codec.encodeFile(manifest));

        EvidencePackV1.EvidenceRefEntry entry =
                new EvidenceRefVerifier(base.root()).verifyWithAuthoritativeLineage(dailyRef);
        assertEquals(EvidenceStatus.UNAVAILABLE, entry.status());
        assertEquals("AMBIGUOUS_FILE_LINEAGE", entry.reasonCode(),
                "a [1,2] vs [1,3] full-list difference must fail closed");
    }

    private static DailyRecordV1 dailyRow(String businessDate, String rawRef, String runId) {
        return dailyRow(businessDate, rawRef, runId, List.of(1));
    }

    private static DailyRecordV1 dailyRow(String businessDate, String rawRef, String runId,
                                          List<Integer> configVersions) {
        return dailyRow(businessDate, rawRef, "pboc-basic-validation-v1", runId, configVersions);
    }

    private static DailyRecordV1 dailyRow(String businessDate, String rawRef, String validationVersion,
                                          String runId) {
        return dailyRow(businessDate, rawRef, validationVersion, runId, List.of(1));
    }

    private static DailyRecordV1 dailyRow(String businessDate, String rawRef, String validationVersion,
                                          String runId, List<Integer> configVersions) {
        return new DailyRecordV1("1.0", businessDate, Day6R2Fixture.ITEM,
                ProviderType.OFFICIAL_WEB, "Row Source A", AccessMethod.PUBLIC_OFFICIAL_HTML,
                ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED, validationVersion, configVersions,
                "arithmetic-mean-v1", 8, 4, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "100.00000000", 1, "100.00000000", 1, 0, true, "CNY", "CNY/1 USD",
                List.of(new DailyInputRefV1(runId, rawRef, 4)), Day6R2Fixture.AT, null);
    }

    /** Persisted two-raw / two-row fixture with a real daily file spanning both raw refs. */
    private static final class MultiRowFixture {
        private final Day6R2Fixture base;
        private final String rawARef;
        private final String rawBRef;
        private final String dailyRef;

        private MultiRowFixture(Day6R2Fixture base, String rawARef, String rawBRef, String dailyRef) {
            this.base = base;
            this.rawARef = rawARef;
            this.rawBRef = rawBRef;
            this.dailyRef = dailyRef;
        }

        private static MultiRowFixture create(Path temp, String leaf, String valueA, String valueB)
                throws Exception {
            Day6R2Fixture base = Day6R2Fixture.create(temp, leaf);
            String rawARef = writeRaw(base, "rowrunA20260808", "Row Source A", "2026-08-08", valueA);
            String rawBRef = writeRaw(base, "rowrunB20260809", "Row Source B", "2026-08-09", valueB);
            String dailyRef = DataPaths.dailyRef(Day6R2Fixture.ITEM, YearMonth.of(2026, 8));
            List<DailyRecordV1> rows = List.of(
                    row(base, "2026-08-08", rawARef, valueA, "rowrunA20260808", "Row Source A", List.of(1)),
                    row(base, "2026-08-09", rawBRef, valueB, "rowrunB20260809", "Row Source B", List.of(2, 3)));
            byte[] data = CsvV1Codec.encodeDaily(rows);
            ManifestV1 manifest = ManifestFactory.csv(dailyRef, data, 2, "2026-08-08", "2026-08-09",
                    List.of("rowrunA20260808", "rowrunB20260809"), Day6R2Fixture.AT);
            Files.write(base.root().resolveDataRef(dailyRef), data);
            Files.write(base.root().resolveDataRef(DataPaths.manifestRef(dailyRef)),
                    JsonV1Codec.encodeFile(manifest));
            return new MultiRowFixture(base, rawARef, rawBRef, dailyRef);
        }

        private static DailyRecordV1 row(Day6R2Fixture base, String businessDate, String rawRef,
                                         String value, String runId, String sourceName,
                                         List<Integer> configVersions) {
            return new DailyRecordV1("1.0", businessDate, Day6R2Fixture.ITEM,
                    ProviderType.OFFICIAL_WEB, sourceName, AccessMethod.PUBLIC_OFFICIAL_HTML,
                    ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED, "pboc-basic-validation-v1",
                    configVersions, "arithmetic-mean-v1", 8, 4, RoundingMode.HALF_UP,
                    "weekday-asia-shanghai-v1", value, 1, value, 1, 0, true, "CNY", "CNY/1 USD",
                    List.of(new DailyInputRefV1(runId, rawRef, 4)), Day6R2Fixture.AT, null);
        }

        private static String writeRaw(Day6R2Fixture base, String runId, String sourceName,
                                       String businessDate, String value) throws Exception {
            String rawRef = RawReceiptV1.deriveRawRef(Mode.FORMAL, ProviderType.OFFICIAL_WEB,
                    Day6R2Fixture.ITEM, Day6R2Fixture.AT, runId);
            byte[] payload = ("payload-" + runId).getBytes(StandardCharsets.UTF_8);
            RawReceiptV1 raw = new RawReceiptV1("1.0", rawRef, "acq-" + runId, runId, Mode.FORMAL,
                    ProviderType.OFFICIAL_WEB, AccessMethod.PUBLIC_OFFICIAL_HTML, 1, sourceName,
                    "https://example.test/" + runId, "ref-" + runId, Day6R2Fixture.ITEM,
                    businessDate, businessDate, null, Day6R2Fixture.AT, Day6R2Fixture.AT, null,
                    value, "CNY/1 USD", "CNY", null, 200, "text/html; charset=UTF-8", "base64",
                    Base64.getEncoder().encodeToString(payload), FileDigest.sha256(payload),
                    "d6-r4", Day6R2Fixture.AT, null, null);
            byte[] data = JsonV1Codec.encodeFile(raw);
            ManifestV1 manifest = ManifestFactory.json(rawRef, data, List.of(runId), Day6R2Fixture.AT);
            Files.write(base.root().resolveDataRef(rawRef), data);
            Files.write(base.root().resolveDataRef(DataPaths.manifestRef(rawRef)),
                    JsonV1Codec.encodeFile(manifest));
            return rawRef;
        }

        private AgentOrchestrator.AgentResult answer(AgentOrchestrator.AgentQueryInput query,
                                                     AgentResponseVerifier verifier,
                                                     LLMService.Port llm) {
            return base.orchestrator(llm, verifier).answer(query);
        }

        private AgentOrchestrator orchestrator(LLMService.Port llm, AgentResponseVerifier verifier) {
            return base.orchestrator(llm, verifier);
        }

        private AgentOrchestrator.AgentQueryInput rangeQuery() {
            return new AgentOrchestrator.AgentQueryInput("analyse two rows", Day6R2Fixture.ITEM,
                    "2026-08-08", "2026-08-09", null, null, null, null, null, "FORMAL");
        }

        private ToolResult historyQuery() {
            return base.historyQuery().historyQuery(Day6R2Fixture.ITEM, "2026-08-08", "2026-08-09",
                    "req-m2-r4");
        }

        private String rawARef() {
            return rawARef;
        }

        private String rawBRef() {
            return rawBRef;
        }
    }
}
