package com.supplymind.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.agent.evidence.EvidenceRefVerifier;
import com.supplymind.agent.evidence.EvidenceStatus;
import com.supplymind.agent.report.AgentReportV1;
import com.supplymind.agent.report.ReportStore;
import com.supplymind.agent.support.Day6R2Fixture;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.ManifestFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DAY6 Round3 M4: complete report/evidence binding - evidenceRefId/refType/ref/sha256/status/
 * reasonCode must ALL match the authoritative re-verification; only after refType equality is
 * the per-type lineage comparison selected (never switch on the frozen, attacker-controlled
 * refType). RAW binds businessDate/configVersions; LIFECYCLE binds publishRef/businessDate.
 * Every attack first proves the untampered report reads PASS and expects exactly ONE code.
 */
class Day6FinalStageFullBindingAttackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void a_refTypeTamperWithReconstructedReportManifestIsBindingMismatch() throws Exception {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "bind-reftype");
        String ref = storeFullReport(fixture, "report-bind-reftype", "req-bind-reftype");
        assertUntamperedPasses(fixture, ref);

        String failure = tamper(fixture, ref, node -> {
            JsonNode daily = findEntry(node, fixture.dailyRef());
            ((ObjectNode) daily).put("refType", "WARNING");
        });
        assertEquals("EVIDENCE_BINDING_MISMATCH", failure,
                "an attacker-modified refType must never steer the comparison into a looser branch");
    }

    @Test
    void b_evidenceRefIdTamperIsBindingMismatch() throws Exception {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "bind-refid");
        String ref = storeFullReport(fixture, "report-bind-refid", "req-bind-refid");
        assertUntamperedPasses(fixture, ref);

        String failure = tamper(fixture, ref, node -> {
            JsonNode raw = findEntry(node, fixture.rawRef());
            ((ObjectNode) raw).put("evidenceRefId", "ev-hacked");
        });
        assertEquals("EVIDENCE_BINDING_MISMATCH", failure);
    }

    @Test
    void c_verifiedEntryReasonCodeTamperIsBindingMismatch() throws Exception {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "bind-reason");
        String ref = storeFullReport(fixture, "report-bind-reason", "req-bind-reason");
        assertUntamperedPasses(fixture, ref);

        String failure = tamper(fixture, ref, node -> {
            JsonNode raw = findEntry(node, fixture.rawRef());
            ((ObjectNode) raw).put("reasonCode", "hacked");
        });
        assertEquals("EVIDENCE_BINDING_MISMATCH", failure,
                "a VERIFIED entry's reasonCode must still equal the authoritative null");
    }

    @Test
    void d_rawBusinessDateAndConfigVersionTamperIsLineageMismatch() throws Exception {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "bind-raw-lineage");
        String ref = storeFullReport(fixture, "report-bind-raw", "req-bind-raw");
        assertUntamperedPasses(fixture, ref);

        String failure = tamper(fixture, ref, node -> {
            JsonNode raw = findEntry(node, fixture.rawRef());
            ((ObjectNode) raw).put("businessDate", "2026-01-01");
            ((ObjectNode) raw).putArray("configVersions").add("99");
        });
        assertEquals("EVIDENCE_LINEAGE_MISMATCH", failure,
                "RAW must bind businessDate and configVersions from the real file");
    }

    @Test
    void e_lifecyclePublishRefAndBusinessDateTamperIsLineageMismatch() throws Exception {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "bind-lifecycle");
        String ref = storeFullReport(fixture, "report-bind-life", "req-bind-life");
        assertUntamperedPasses(fixture, ref);

        String failure = tamper(fixture, ref, node -> {
            JsonNode staging = findEntry(node, fixtureStagingRef());
            ((ObjectNode) staging).put("publishRef", "staging/hacked.json#recordVersion=4");
            ((ObjectNode) staging).put("businessDate", "2026-01-01");
        });
        assertEquals("EVIDENCE_LINEAGE_MISMATCH", failure,
                "LIFECYCLE must bind publishRef and businessDate from the real file");
    }

    // ---- helpers ----

    private static void assertUntamperedPasses(Day6R2Fixture fixture, String ref) {
        ReportStore.ReadResult result = new ReportStore(fixture.root(), fixture.files()).read(ref);
        assertTrue(result.ok(),
                "the untampered report must read PASS first (failure=" + result.failureCode() + ")");
    }

    /** Applies the tamper to the frozen report JSON, synchronizes the report manifest, re-reads. */
    private static String tamper(Day6R2Fixture fixture, String ref, Tamper tamper) throws Exception {
        Path reportPath = fixture.root().resolveDataRef(ref);
        byte[] original = Files.readAllBytes(reportPath);
        JsonNode root = MAPPER.readTree(original);
        // Prove the pure read-modify-write round trip (no semantic change) still passes before
        // applying the actual tamper - isolating serialization from the binding attack.
        byte[] untouched = new String(MAPPER.writeValueAsBytes(root), java.nio.charset.StandardCharsets.UTF_8)
                .concat("\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ManifestV1 untouchedManifest = ManifestFactory.json(ref, untouched, List.of(), Day6R2Fixture.AT);
        Files.write(reportPath, untouched);
        Files.write(fixture.root().resolveDataRef(DataPaths.manifestRef(ref)),
                JsonV1Codec.encodeFile(untouchedManifest));
        ReportStore.ReadResult roundTrip = new ReportStore(fixture.root(), fixture.files()).read(ref);
        if (!roundTrip.ok()) {
            throw new IllegalStateException("round-trip failed before tamper: " + roundTrip.failureCode());
        }
        tamper.apply(root);
        byte[] forged = new String(MAPPER.writeValueAsBytes(root), java.nio.charset.StandardCharsets.UTF_8)
                .concat("\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ManifestV1 newManifest = ManifestFactory.json(ref, forged, List.of(), Day6R2Fixture.AT);
        Files.write(reportPath, forged);
        Files.write(fixture.root().resolveDataRef(DataPaths.manifestRef(ref)),
                JsonV1Codec.encodeFile(newManifest));
        ReportStore.ReadResult after = new ReportStore(fixture.root(), fixture.files()).read(ref);
        return after.failureCode();
    }

    private static JsonNode findEntry(JsonNode root, String evidenceRef) {
        JsonNode entries = root.path("evidencePack").path("evidenceRefs");
        for (JsonNode entry : entries) {
            if (evidenceRef.equals(entry.path("ref").asText())) {
                return entry;
            }
        }
        throw new IllegalStateException("entry not found: " + evidenceRef);
    }

    private String fixtureStagingRef() {
        return DataPaths.stagingRef(Day6R2Fixture.RUN_ID);
    }

    /** Full report: RAW + DAILY + LIFECYCLE evidence entries, each with authoritative lineage. */
    private static String storeFullReport(Day6R2Fixture fixture, String reportId, String requestId)
            throws Exception {
        DataRootStaging staging = new DataRootStaging(fixture);
        staging.writeLifecycleTimeline();
        AgentReportV1 report = fullReport(fixture, reportId, requestId);
        return fixture.reportStore().store(report);
    }

    private static AgentReportV1 fullReport(Day6R2Fixture fixture, String reportId, String requestId) {
        EvidencePackV1 base = fixture.verifiedEvidencePack(requestId);
        EvidenceRefVerifier verifier = new EvidenceRefVerifier(fixture.root());
        List<EvidencePackV1.EvidenceRefEntry> refs = new ArrayList<>(base.evidenceRefs());
        refs.add(verifier.verifyWithAuthoritativeLineage(fixture.dailyRef()));
        refs.add(verifier.verifyWithAuthoritativeLineage(DataPaths.stagingRef(Day6R2Fixture.RUN_ID)));
        EvidencePackV1 pack = new EvidencePackV1(
                base.schemaVersion(), base.evidencePackId(), base.requestId(), base.mode(),
                base.question(), base.createdAt(), base.scope(), base.toolExecutions(),
                base.facts(), refs, base.warnings(), base.notices(), base.limitations());
        AgentReportV1 original = fixture.validReport(reportId, requestId);
        return new AgentReportV1(
                original.schemaVersion(), original.reportId(), original.requestId(), pack,
                original.generatedBy(), original.provider(), original.model(),
                original.degraded(), original.degradeReason(), original.factsSummary(),
                original.claims(), original.recommendations(), original.limitations(),
                original.createdAt());
    }

    @FunctionalInterface
    private interface Tamper {
        void apply(JsonNode root);
    }

    private static final class DataRootStaging {
        private final Day6R2Fixture fixture;

        private DataRootStaging(Day6R2Fixture fixture) {
            this.fixture = fixture;
        }

        private void writeLifecycleTimeline() throws Exception {
            String stagingRef = DataPaths.stagingRef(Day6R2Fixture.RUN_ID);
            LifecycleTimelineV1 timeline = LifecycleTimelineV1.initial(
                    Day6R2Fixture.RUN_ID, Day6R2Fixture.RUN_ID, fixture.rawRef(), Day6R2Fixture.AT);
            byte[] data = JsonV1Codec.encodeFile(timeline);
            ManifestV1 manifest = ManifestFactory.json(stagingRef, data,
                    List.of(Day6R2Fixture.RUN_ID), Day6R2Fixture.AT);
            Path target = fixture.root().resolveDataRef(stagingRef);
            Files.createDirectories(target.getParent());
            Files.write(target, data);
            Path manifestTarget = fixture.root().resolveDataRef(DataPaths.manifestRef(stagingRef));
            Files.createDirectories(manifestTarget.getParent());
            Files.write(manifestTarget, JsonV1Codec.encodeFile(manifest));
        }
    }
}
