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
import com.supplymind.foundation.model.AggregateGrain;
import com.supplymind.foundation.model.AggregateInputRefV1;
import com.supplymind.foundation.model.AggregateRecordV1;
import com.supplymind.foundation.model.CanonicalJsonV1;
import com.supplymind.foundation.model.DailyInputRefV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.QualityStatus;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.DirtyTargetRole;
import com.supplymind.foundation.storage.DirtyTransactionType;
import com.supplymind.foundation.storage.FileTransactionTarget;
import com.supplymind.foundation.storage.ManifestFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DAY6 Round3 M2: multi-row file authoritative lineage - the first row never represents a file.
 * Uniform lineage across ALL rows is required; heterogeneous lineage fails closed as
 * UNAVAILABLE/AMBIGUOUS_FILE_LINEAGE; a manifest-valid but undecodable file is
 * INVALID/SCHEMA_DECODE_FAILED; both stay in the four-state EvidencePack but never reach the
 * Phase B LLM context; the real production HistoryQuery adapter fills evidenceLineageByRef.
 */
class Day6FinalStageMultiRowLineageAttackTest {

    @TempDir
    Path temp;

    @Test
    void a_dailyFileWithUniformMultiRowLineageIsVerifiedWithFileLevelLineage() throws Exception {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "m2-uniform");
        DataRoot root = fixture.root();
        AtomicFileStore files = fixture.files();
        String ref = DataPaths.dailyRef(Day6R2Fixture.ITEM, YearMonth.of(2026, 8));
        byte[] data = CsvV1Codec.encodeDaily(List.of(
                daily("2026-08-08", "pboc-basic-validation-v1", Day6R2Fixture.RUN_ID),
                daily("2026-08-09", "pboc-basic-validation-v1", Day6R2Fixture.RUN_ID)));
        ManifestV1 manifest = ManifestFactory.csv(ref, data, 2, "2026-08-08", "2026-08-09",
                List.of(Day6R2Fixture.RUN_ID), Day6R2Fixture.AT);
        Files.write(root.resolveDataRef(ref), data);
        Files.write(root.resolveDataRef(DataPaths.manifestRef(ref)), JsonV1Codec.encodeFile(manifest));

        EvidencePackV1.EvidenceRefEntry entry =
                new EvidenceRefVerifier(root).verifyWithAuthoritativeLineage(ref);
        assertEquals(EvidenceStatus.VERIFIED, entry.status());
        assertEquals("pboc-basic-validation-v1", entry.validationVersion());
        assertEquals("arithmetic-mean-v1", entry.calculationVersion());
        assertEquals("weekday-asia-shanghai-v1", entry.calendarVersion());
        assertEquals(List.of("1"), entry.configVersions());
        assertEquals("2026-08-08", entry.businessDate(),
                "the file-level business date is the manifest min, never a picked row");
        assertEquals("2026-08-08", entry.periodStart());
        assertEquals("2026-08-09", entry.periodEnd());
    }

    @Test
    void b_dailyFileWithHeterogeneousValidationVersionFailsClosedAsAmbiguous() throws Exception {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "m2-daily-hetero");
        DataRoot root = fixture.root();
        String ref = DataPaths.dailyRef(Day6R2Fixture.ITEM, YearMonth.of(2026, 8));
        byte[] data = CsvV1Codec.encodeDaily(List.of(
                daily("2026-08-08", "pboc-basic-validation-v1", Day6R2Fixture.RUN_ID),
                daily("2026-08-09", "pboc-strict-validation-v2", Day6R2Fixture.RUN_ID)));
        ManifestV1 manifest = ManifestFactory.csv(ref, data, 2, "2026-08-08", "2026-08-09",
                List.of(Day6R2Fixture.RUN_ID), Day6R2Fixture.AT);
        Files.write(root.resolveDataRef(ref), data);
        Files.write(root.resolveDataRef(DataPaths.manifestRef(ref)), JsonV1Codec.encodeFile(manifest));

        EvidencePackV1.EvidenceRefEntry entry =
                new EvidenceRefVerifier(root).verifyWithAuthoritativeLineage(ref);
        assertEquals(EvidenceStatus.UNAVAILABLE, entry.status(),
                "the first row must never represent a heterogeneous file");
        assertEquals("AMBIGUOUS_FILE_LINEAGE", entry.reasonCode());
    }

    @Test
    void c_aggregateFileWithHeterogeneousCalculationVersionFailsClosedAsAmbiguous() throws Exception {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "m2-aggregate-hetero");
        DataRoot root = fixture.root();
        String ref = DataPaths.aggregateRef(Day6R2Fixture.ITEM, "month", 2026);
        String dailyHash = JsonV1Codec.decodeFile(Files.readAllBytes(
                root.resolveDataRef(DataPaths.manifestRef(fixture.dailyRef()))), ManifestV1.class).fileSha256();
        byte[] data = CsvV1Codec.encodeAggregate(List.of(
                aggregate("2026-07", "100.00000000", "arithmetic-mean-v1", fixture.dailyRef(), dailyHash),
                aggregate("2026-08", "123.45678901", "geometric-mean-v2", fixture.dailyRef(), dailyHash)));
        ManifestV1 manifest = ManifestFactory.csv(ref, data, 2, "2026-07-01", "2026-08-31",
                List.of(Day6R2Fixture.RUN_ID), Day6R2Fixture.AT);
        Files.write(root.resolveDataRef(ref), data);
        Files.write(root.resolveDataRef(DataPaths.manifestRef(ref)), JsonV1Codec.encodeFile(manifest));

        EvidencePackV1.EvidenceRefEntry entry =
                new EvidenceRefVerifier(root).verifyWithAuthoritativeLineage(ref);
        assertEquals(EvidenceStatus.UNAVAILABLE, entry.status());
        assertEquals("AMBIGUOUS_FILE_LINEAGE", entry.reasonCode());
    }

    @Test
    void d_corruptCsvWithSynchronizedManifestIsInvalidSchemaDecodeFailed() throws Exception {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "m2-corrupt-csv");
        DataRoot root = fixture.root();
        String ref = DataPaths.dailyRef(Day6R2Fixture.ITEM, YearMonth.of(2026, 8));
        byte[] data = "not,a,csv,at,all".getBytes(StandardCharsets.UTF_8);
        ManifestV1 manifest = ManifestFactory.csv(ref, data, 1, "2026-08-10", "2026-08-10",
                List.of(Day6R2Fixture.RUN_ID), Day6R2Fixture.AT);
        Files.write(root.resolveDataRef(ref), data);
        Files.write(root.resolveDataRef(DataPaths.manifestRef(ref)), JsonV1Codec.encodeFile(manifest));

        EvidencePackV1.EvidenceRefEntry entry =
                new EvidenceRefVerifier(root).verifyWithAuthoritativeLineage(ref);
        assertEquals(EvidenceStatus.INVALID, entry.status(),
                "a manifest-valid but undecodable file must never stay VERIFIED");
        assertTrue("MANIFEST_MISMATCH".equals(entry.reasonCode())
                        || "SCHEMA_DECODE_FAILED".equals(entry.reasonCode()),
                "reasonCode=" + entry.reasonCode()
                        + " (manifest derived-field validation or the decode defense layer must fail closed)");
    }

    @Test
    void e_corruptRawReceiptJsonWithSynchronizedManifestIsInvalidSchemaDecodeFailed() throws Exception {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "m2-corrupt-raw");
        DataRoot root = fixture.root();
        byte[] data = "{this is not valid json".getBytes(StandardCharsets.UTF_8);
        ManifestV1 manifest = ManifestFactory.json(fixture.rawRef(), data,
                List.of(Day6R2Fixture.RUN_ID), Day6R2Fixture.AT);
        Files.write(root.resolveDataRef(fixture.rawRef()), data);
        Files.write(root.resolveDataRef(DataPaths.manifestRef(fixture.rawRef())),
                JsonV1Codec.encodeFile(manifest));

        EvidencePackV1.EvidenceRefEntry entry =
                new EvidenceRefVerifier(root).verifyWithAuthoritativeLineage(fixture.rawRef());
        assertEquals(EvidenceStatus.INVALID, entry.status());
        assertTrue("MANIFEST_MISMATCH".equals(entry.reasonCode())
                        || "SCHEMA_DECODE_FAILED".equals(entry.reasonCode()),
                "reasonCode=" + entry.reasonCode());
    }

    @Test
    void f_invalidEvidenceStaysInFourStatePackButNeverReachesPhaseBLlmRequest() throws Exception {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "m2-four-state");
        DataRoot root = fixture.root();
        // Corrupt the raw file AND synchronize its manifest so only the schema decode fails.
        byte[] data = "{corrupt".getBytes(StandardCharsets.UTF_8);
        ManifestV1 manifest = ManifestFactory.json(fixture.rawRef(), data,
                List.of(Day6R2Fixture.RUN_ID), Day6R2Fixture.AT);
        Files.write(root.resolveDataRef(fixture.rawRef()), data);
        Files.write(root.resolveDataRef(DataPaths.manifestRef(fixture.rawRef())),
                JsonV1Codec.encodeFile(manifest));

        AtomicReference<LLMService.LLMRequest> requestSeen = new AtomicReference<>();
        AgentOrchestrator.AgentResult result = fixture.orchestrator(request -> {
            requestSeen.set(request);
            return LLMService.LLMResponse.success(
                    "{\"answer\":\"ok\",\"claims\":[{\"claimId\":\"c1\",\"text\":\"ok\","
                            + "\"factIds\":[\"fact-0\"],\"evidenceRefs\":[]}]}");
        }, new AgentResponseVerifier(List.of())).answer(fixture.formalHistoryQuery());

        assertTrue(result.evidencePack().evidenceRefs().stream()
                        .anyMatch(entry -> entry.ref().equals(fixture.rawRef())
                                && entry.status() == EvidenceStatus.INVALID
                                && ("MANIFEST_MISMATCH".equals(entry.reasonCode())
                                || "SCHEMA_DECODE_FAILED".equals(entry.reasonCode()))),
                "M2 four-state: the INVALID ref stays in the EvidencePack with its reasonCode");
        assertNotNull(requestSeen.get());
        assertFalse(requestSeen.get().evidenceRefs().contains(fixture.rawRef()),
                "the INVALID ref must never reach the Phase B LLM request");
        assertFalse(requestSeen.get().facts().stream()
                        .anyMatch(fact -> fact.evidenceRef() != null
                                && fact.evidenceRef().equals(fixture.rawRef())),
                "the INVALID ref must never back a Phase B fact");
    }

    @Test
    void g_realProductionAdapterFillsPerRefEvidenceLineage() throws Exception {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "m2-per-ref");
        HistoryQueryToolAdapter adapter = fixture.historyQuery();
        ToolResult result = adapter.historyQuery(Day6R2Fixture.ITEM,
                "2026-08-10", "2026-08-10", "req-m2-g");
        assertEquals(ToolStatus.SUCCESS, result.status());
        assertFalse(result.evidenceLineageByRef().isEmpty(),
                "the real production adapter must fill evidenceLineageByRef");
        for (String ref : result.evidenceRefs()) {
            ToolResult.Lineage perRef = result.evidenceLineageByRef().get(ref);
            assertNotNull(perRef, "every evidenceRef must have its own real lineage: " + ref);
            assertEquals("pboc-basic-validation-v1", perRef.validationVersion());
        }
    }

    private static DailyRecordV1 daily(String businessDate, String validationVersion, String runId) {
        return new DailyRecordV1("1.0", businessDate, Day6R2Fixture.ITEM,
                ProviderType.OFFICIAL_WEB, Day6R2Fixture.SOURCE, AccessMethod.PUBLIC_OFFICIAL_HTML,
                ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED, validationVersion, List.of(1),
                "arithmetic-mean-v1", 8, 4, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "123.45678901", 1, "123.45678901", 1, 0, true, "CNY", "CNY/1 USD",
                List.of(new DailyInputRefV1(runId, "raw/formal/official_web/X/2026/08/x.json", 4)),
                Day6R2Fixture.AT, null);
    }

    private static AggregateRecordV1 aggregate(String monthKey, String avg, String calculationVersion,
                                               String dailyRef, String dailyHash) {
        YearMonth month = YearMonth.parse(monthKey);
        String hash = CanonicalJsonV1.sha256LowerHex(CanonicalJsonV1.sourceIdentity(
                ProviderType.OFFICIAL_WEB, Day6R2Fixture.SOURCE, AccessMethod.PUBLIC_OFFICIAL_HTML));
        return new AggregateRecordV1("1.0", AggregateGrain.MONTH, month.atDay(1).toString(),
                month.atEndOfMonth().toString(), Day6R2Fixture.ITEM, ProviderType.OFFICIAL_WEB,
                Day6R2Fixture.SOURCE, AccessMethod.PUBLIC_OFFICIAL_HTML, ValidationStatus.VERIFIED,
                "pboc-basic-validation-v1", List.of(1), calculationVersion, 8, 4, RoundingMode.HALF_UP,
                "weekday-asia-shanghai-v1", avg, 1, avg, avg, avg, 1, 0, true,
                QualityStatus.COMPLETE, "CNY", "CNY/1 USD", hash,
                List.of(new AggregateInputRefV1(dailyRef, month.atDay(1).toString(),
                        "pboc-basic-validation-v1", dailyHash)), Day6R2Fixture.AT, null);
    }
}
