package com.supplymind.agent;

import com.supplymind.agent.report.ReportStore;
import com.supplymind.agent.support.Day6R2Fixture;
import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AggregateRecordV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.ManifestFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DAY6 final stage M4: ReportStore.read recovers the AUTHORITATIVE lineage by decoding the real
 * evidence files (never the report's self-reported lineage) and binds every applicable frozen
 * field. The attack fixture uses the REAL manifest fileSha256 in downstream provenance, and a
 * content tamper that keeps the manifest consistent is still caught by the evidence binding.
 */
class Day6FinalStageAuthorityBindingAttackTest {

    @TempDir
    Path temp;

    @Test
    void untamperedReportPassesBeforeAnyTamperIsProven() throws Exception {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "binding-ok");
        String ref = fixture.reportStore().store(fixture.validReport("report-binding-ok", "req-binding-ok"));

        ReportStore.ReadResult first = new ReportStore(fixture.root(), fixture.files()).read(ref);
        assertTrue(first.ok(), "the untampered report must read clean first (failure=" + first.failureCode() + ")");

        ReportStore.ReadResult second = new ReportStore(fixture.root(), fixture.files()).read(ref);
        assertTrue(second.ok(), "a second independent read must also pass (failure=" + second.failureCode() + ")");
    }

    @Test
    void contentTamperWithSynchronizedManifestIsCaughtByEvidenceBinding() throws Exception {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "binding-tamper");
        String ref = fixture.reportStore().store(
                reportWithDailyRef(fixture, "report-binding-tamper", "req-binding-tamper"));

        ReportStore.ReadResult before = new ReportStore(fixture.root(), fixture.files()).read(ref);
        assertTrue(before.ok(), "prove the untampered state PASSES before attacking");

        // Attacker rewrites the daily CSV content AND synchronizes its manifest (new content hash,
        // same row count/date range) - the report is unchanged.
        Path daily = fixture.root().resolveDataRef(fixture.dailyRef());
        byte[] original = Files.readAllBytes(daily);
        byte[] tampered = new String(original, StandardCharsets.UTF_8)
                .replace("123.45678901", "999.99999999").getBytes(StandardCharsets.UTF_8);
        Files.write(daily, tampered);
        ManifestV1 newManifest = ManifestFactory.csv(fixture.dailyRef(), tampered, 1,
                "2026-08-10", "2026-08-10", java.util.List.of(Day6R2Fixture.RUN_ID), Day6R2Fixture.AT);
        Files.write(fixture.root().resolveDataRef(DataPaths.manifestRef(fixture.dailyRef())),
                JsonV1Codec.encodeFile(newManifest));

        ReportStore.ReadResult after = new ReportStore(fixture.root(), fixture.files()).read(ref);
        assertFalse(after.ok(), "a content tamper with a consistent manifest must fail closed");
        assertEquals("EVIDENCE_BINDING_MISMATCH", after.failureCode(),
                "the frozen sha256/lineage binding must catch the drift (got " + after.failureCode() + ")");
    }

    private static com.supplymind.agent.report.AgentReportV1 reportWithDailyRef(
            Day6R2Fixture fixture, String reportId, String requestId
    ) {
        com.supplymind.agent.evidence.EvidencePackV1 base =
                fixture.verifiedEvidencePack(requestId);
        java.util.List<com.supplymind.agent.evidence.EvidencePackV1.EvidenceRefEntry> refs =
                new java.util.ArrayList<>(base.evidenceRefs());
        refs.add(new com.supplymind.agent.evidence.EvidenceRefVerifier(fixture.root())
                .verifyWithAuthoritativeLineage(fixture.dailyRef()));
        com.supplymind.agent.evidence.EvidencePackV1 pack =
                new com.supplymind.agent.evidence.EvidencePackV1(
                        base.schemaVersion(), base.evidencePackId(), base.requestId(), base.mode(),
                        base.question(), base.createdAt(), base.scope(), base.toolExecutions(),
                        base.facts(), refs, base.warnings(), base.notices(), base.limitations());
        com.supplymind.agent.report.AgentReportV1 original =
                fixture.validReport(reportId, requestId);
        return new com.supplymind.agent.report.AgentReportV1(
                original.schemaVersion(), original.reportId(), original.requestId(), pack,
                original.generatedBy(), original.provider(), original.model(),
                original.degraded(), original.degradeReason(), original.factsSummary(),
                original.claims(), original.recommendations(), original.limitations(),
                original.createdAt());
    }

    @Test
    void aggregateAttackFixtureCarriesTheRealDailyManifestFileSha256() throws Exception {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "binding-fixture");
        ManifestV1 dailyManifest = JsonV1Codec.decodeFile(
                Files.readAllBytes(fixture.root().resolveDataRef(DataPaths.manifestRef(fixture.dailyRef()))),
                ManifestV1.class);
        AggregateRecordV1 row = CsvV1Codec.decodeAggregate(
                Files.readAllBytes(fixture.root().resolveDataRef(fixture.aggregateRef()))).get(0);
        assertFalse(row.inputRefs().isEmpty());
        assertEquals(dailyManifest.fileSha256(), row.inputRefs().get(0).fileSha256(),
                "the attack fixture must provenance the REAL manifest fileSha256, not a placeholder");
    }
}
