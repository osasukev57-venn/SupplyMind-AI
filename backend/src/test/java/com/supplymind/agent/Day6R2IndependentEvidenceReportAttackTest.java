package com.supplymind.agent;

import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.agent.evidence.EvidenceRefVerifier;
import com.supplymind.agent.evidence.EvidenceStatus;
import com.supplymind.agent.llm.LLMService;
import com.supplymind.agent.orchestration.AgentOrchestrator;
import com.supplymind.agent.report.AgentReportV1;
import com.supplymind.agent.report.ReportStore;
import com.supplymind.agent.support.Day6R2Fixture;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.ManifestFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Independent M2/M5 attacks against evidence lineage, invalid evidence handling and restart reads. */
class Day6R2IndependentEvidenceReportAttackTest {

    @TempDir
    Path temp;

    @Test
    void formalFactsCarryEveryRequiredNonPlaceholderLineageField() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "lineage");
        AgentOrchestrator.AgentResult result = fixture.orchestrator(
                success("Value is 123.45678901"), new AgentResponseVerifier(List.of()))
                .answer(fixture.formalHistoryQuery());

        assertFalse(result.evidencePack().facts().isEmpty());
        for (EvidencePackV1.Fact fact : result.evidencePack().facts()) {
            assertNonBlank(fact.unit(), "unit");
            assertNonBlank(fact.currency(), "currency");
            assertNonBlank(fact.validationStatus(), "validationStatus");
            assertNonBlank(fact.validationVersion(), "validationVersion");
            assertNonBlank(fact.calculationVersion(), "calculationVersion");
            assertNonBlank(fact.calendarVersion(), "calendarVersion");
            assertFalse(fact.configVersions().isEmpty(), "configVersions is required lineage");
            assertNonBlank(fact.actualSourceName(), "actualSourceName");
            assertNonBlank(fact.sourceFingerprint(), "sourceFingerprint");
            assertFalse(fact.evidenceRefs().isEmpty(), "facts must have verified evidence refs");
        }
        for (EvidencePackV1.EvidenceRefEntry entry : result.evidencePack().evidenceRefs()) {
            assertEquals(EvidenceStatus.VERIFIED, entry.status());
            assertNonBlank(entry.sha256(), "verified sha256");
            // M2/M4: lineage fields are checked per refType - a RAW file has no
            // validation/calculation/calendar/config versions, a CONFIG file has only
            // configVersions; the REAL FILE is the authority for every applicable field.
            switch (entry.refType()) {
                case "RAW" -> {
                    assertNonBlank(entry.runId(), "raw runId");
                    assertNonBlank(entry.rawRef(), "raw rawRef");
                }
                case "CONFIG" -> assertFalse(entry.configVersions().isEmpty(),
                        "config configVersions are required");
                default -> {
                    assertNonBlank(entry.validationVersion(), "evidence validationVersion");
                    assertNonBlank(entry.calculationVersion(), "evidence calculationVersion");
                    assertNonBlank(entry.calendarVersion(), "evidence calendarVersion");
                    assertFalse(entry.configVersions().isEmpty(),
                            "evidence configVersions are required");
                }
            }
        }
    }

    @Test
    void missingInvalidAndUnsafeEvidenceNeverReachVerifiedLlmContext() throws Exception {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "invalid-evidence");
        EvidenceRefVerifier verifier = new EvidenceRefVerifier(fixture.root());
        assertEquals(EvidenceStatus.MISSING, verifier.verify("raw/formal/official_web/FX.X/2026/08/nope.json").status());
        assertEquals(EvidenceStatus.UNAVAILABLE, verifier.verify("../outside.json").status());

        Files.writeString(fixture.root().resolveDataRef(fixture.rawRef()), "tampered", StandardCharsets.UTF_8);
        assertEquals(EvidenceStatus.INVALID, verifier.verify(fixture.rawRef()).status());

        AtomicReference<LLMService.LLMRequest> requestSeen = new AtomicReference<>();
        AgentOrchestrator.AgentResult result = fixture.orchestrator(request -> {
            requestSeen.set(request);
            return LLMService.LLMResponse.success("no verified evidence should be used");
        }, new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());

        // M2 four-state: the INVALID raw stays in the structured EvidencePack audit trail with
        // its status, but never reaches the LLM context, the facts or the verified view.
        assertTrue(result.evidencePack().evidenceRefs().stream()
                        .anyMatch(entry -> entry.ref().equals(fixture.rawRef())
                                && entry.status() == EvidenceStatus.INVALID),
                "M2 four-state: INVALID raw must be present with its status in the EvidencePack");
        assertTrue(result.evidencePack().facts().isEmpty(), "invalid raw cannot create an LLM fact");
        assertNotNull(requestSeen.get());
        assertFalse(requestSeen.get().evidenceRefs().contains(fixture.rawRef()));
        assertTrue(result.evidencePack().limitations().stream().anyMatch(text -> text.contains("INVALID")));
    }

    @Test
    void reportRestartReadRevalidatesPathManifestBodyMonthAndEvidence() throws Exception {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "restart");
        AgentReportV1 report = fixture.validReport("report-r2-restart", "req-r2-restart");
        String ref = fixture.reportStore().store(report);

        ReportStore.ReadResult restarted = new ReportStore(fixture.root(), fixture.files()).read(ref);
        assertTrue(restarted.ok());
        assertEquals(report.reportId(), restarted.report().reportId());

        Files.writeString(fixture.root().resolveDataRef(fixture.rawRef()), "evidence-tamper", StandardCharsets.UTF_8);
        ReportStore.ReadResult evidenceChanged = new ReportStore(fixture.root(), fixture.files()).read(ref);
        assertFalse(evidenceChanged.ok());
        assertEquals("EVIDENCE_UNAVAILABLE", evidenceChanged.failureCode());
    }

    @Test
    void reportBodyAndManifestTamperFailClosedOnFreshStoreInstance() throws Exception {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "tamper");
        String ref = fixture.reportStore().store(fixture.validReport("report-r2-body", "req-r2-body"));
        Path body = fixture.root().resolveDataRef(ref);
        Files.writeString(body, Files.readString(body, StandardCharsets.UTF_8).replace("JAVA_TEMPLATE", "LLM"),
                StandardCharsets.UTF_8);
        ReportStore.ReadResult bodyResult = new ReportStore(fixture.root(), fixture.files()).read(ref);
        assertFalse(bodyResult.ok());
        assertEquals("MANIFEST_MISMATCH", bodyResult.failureCode());

        Day6R2Fixture manifestFixture = Day6R2Fixture.create(temp, "manifest-tamper");
        String manifestRef = manifestFixture.reportStore().store(
                manifestFixture.validReport("report-r2-manifest", "req-r2-manifest"));
        Path manifest = manifestFixture.root().resolveDataRef(DataPaths.manifestRef(manifestRef));
        Files.writeString(manifest, Files.readString(manifest, StandardCharsets.UTF_8) + " ", StandardCharsets.UTF_8);
        ReportStore.ReadResult manifestResult = new ReportStore(manifestFixture.root(), manifestFixture.files()).read(manifestRef);
        assertFalse(manifestResult.ok());
        assertEquals("MANIFEST_MISMATCH", manifestResult.failureCode());
    }

    @Test
    void reportIdentityDriftAndIllegalReportIdsAreRejectedEvenWithAValidReplacementManifest() throws Exception {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "identity");
        AgentReportV1 original = fixture.validReport("report-r2-identity", "req-r2-identity");
        String ref = fixture.reportStore().store(original);
        AgentReportV1 forged = new AgentReportV1(original.schemaVersion(), "report-r2-other", original.requestId(),
                original.evidencePack(), original.generatedBy(), original.provider(), original.model(),
                original.degraded(), original.degradeReason(), original.factsSummary(), original.claims(),
                original.recommendations(), original.limitations(), original.createdAt());
        byte[] forgedBytes = JsonV1Codec.encodeFile(forged);
        ManifestV1 forgedManifest = ManifestFactory.json(ref, forgedBytes, List.of(), original.createdAt());
        Files.write(fixture.root().resolveDataRef(ref), forgedBytes);
        Files.write(fixture.root().resolveDataRef(DataPaths.manifestRef(ref)), JsonV1Codec.encodeFile(forgedManifest));

        ReportStore.ReadResult identity = new ReportStore(fixture.root(), fixture.files()).read(ref);
        assertFalse(identity.ok());
        assertEquals("IDENTITY_MISMATCH", identity.failureCode());
        assertFalse(new ReportStore(fixture.root(), fixture.files()).read("report/2026-08/../bad.json").ok());
        assertFalse(new ReportStore(fixture.root(), fixture.files()).read("report/2026-08/not-json").ok());
    }


    @Test
    void reportMonthPathMustMatchCreatedAtEvenWhenTheReplacementManifestIsValid() throws Exception {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "month-identity");
        AgentReportV1 original = fixture.validReport("report-r2-month", "req-r2-month");
        String ref = fixture.reportStore().store(original);
        AgentReportV1 forged = new AgentReportV1(original.schemaVersion(), original.reportId(), original.requestId(),
                original.evidencePack(), original.generatedBy(), original.provider(), original.model(),
                original.degraded(), original.degradeReason(), original.factsSummary(), original.claims(),
                original.recommendations(), original.limitations(),
                OffsetDateTime.parse("2026-09-01T10:00:00+08:00"));
        byte[] forgedBytes = JsonV1Codec.encodeFile(forged);
        ManifestV1 forgedManifest = ManifestFactory.json(ref, forgedBytes, List.of(), forged.createdAt());
        Files.write(fixture.root().resolveDataRef(ref), forgedBytes);
        Files.write(fixture.root().resolveDataRef(DataPaths.manifestRef(ref)), JsonV1Codec.encodeFile(forgedManifest));

        ReportStore.ReadResult result = new ReportStore(fixture.root(), fixture.files()).read(ref);
        assertFalse(result.ok());
        assertEquals("MONTH_MISMATCH", result.failureCode());
    }

    private static LLMService.Port success(String answer) {
        return request -> LLMService.LLMResponse.success(answer);
    }

    private static void assertNonBlank(String value, String field) {
        assertNotNull(value, field + " must not be null");
        assertFalse(value.isBlank(), field + " must not be blank");
    }
}
