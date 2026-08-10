package com.supplymind.publish;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.CandidateV1;
import com.supplymind.foundation.model.LifecycleSnapshotV1;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.QuarantineProjectionV1;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.SchemaV1;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.FileDigest;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.QuarantineStore;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.validation.LifecycleValidationService;
import com.supplymind.validation.PbocCandidateStandardizer;
import com.supplymind.validation.ValidationOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishGateTest {

    private static final ZoneId SHANGHAI = DataPaths.SHANGHAI;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-10T01:02:00Z"), SHANGHAI);
    private static final OffsetDateTime RECEIVED_AT = OffsetDateTime.ofInstant(
            Instant.parse("2026-08-10T01:00:00Z"), SHANGHAI);
    private static final OffsetDateTime PUBLISHED_AT = OffsetDateTime.parse("2026-08-10T09:25:38+08:00");
    private static final String SOURCE_NAME = MonitorSeriesDefaults.PBOC_SOURCE_NAME;
    private static final String FIXTURE_ROOT = "contracts/v1/valid/";

    @TempDir
    Path temporaryDirectory;

    @Test
    void publishesVerifiedRunToPublishedWithGoldenBytes() throws IOException {
        Harness harness = harness();
        RawReceiptV1 raw = pbocRaw("run-publish-golden-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", "CNY/1 USD", "CNY");
        ingest(harness, raw);
        ValidationOutcome validated = harness.validation().process(raw.runId());
        assertEquals(ValidationStatus.VERIFIED, validated.validationStatus());

        PublishOutcome outcome = harness.publish().process(raw.runId());

        assertEquals(PublishOutcome.PublishAction.PUBLISHED, outcome.action());
        assertEquals(4, outcome.recordVersion());
        assertEquals(ProcessingStage.PUBLISHED, outcome.processingStage());
        assertEquals(ValidationStatus.VERIFIED, outcome.validationStatus());
        assertEquals("staging/run-publish-golden-001.json#recordVersion=4", outcome.publishRef());
        assertNull(outcome.quarantineRef());
        byte[] golden = fixtureBytes("lifecycle-published-pboc-v1.json");
        assertArrayEquals(golden, Files.readAllBytes(
                        harness.root().resolveDataRef(DataPaths.stagingRef(raw.runId()))),
                "the persisted published timeline must match the hand-authored golden bytes");
        assertFalse(Files.exists(harness.root().path().resolve("quarantine")),
                "a valid published run must never produce quarantine");
    }

    @Test
    void publishesVerifiedWithNoticeKeepingReasonCodeAndAuditFields() throws IOException {
        Harness harness = harness();
        RawReceiptV1 first = pbocRaw("run-publish-notice-a-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", "CNY/1 USD", "CNY");
        RawReceiptV1 duplicate = pbocRaw("run-publish-notice-b-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", "CNY/1 USD", "CNY");
        ingest(harness, first);
        ingest(harness, duplicate);
        harness.validation().process(first.runId());
        ValidationOutcome notice = harness.validation().process(duplicate.runId());
        assertEquals(ValidationStatus.VERIFIED_WITH_NOTICE, notice.validationStatus());
        assertNotNull(notice.reasonCode());

        PublishOutcome outcome = harness.publish().process(duplicate.runId());

        assertEquals(PublishOutcome.PublishAction.PUBLISHED, outcome.action());
        assertEquals(ValidationStatus.VERIFIED_WITH_NOTICE, outcome.validationStatus());
        assertEquals(notice.reasonCode(), outcome.reasonCode());
        LifecycleTimelineV1 persisted = harness.timelineStore().read(duplicate.runId());
        LifecycleSnapshotV1 published = persisted.current();
        assertEquals(4, published.recordVersion());
        assertEquals(published.candidate(), persisted.records().get(2).candidate());
        assertEquals(published.validationVersion(), persisted.records().get(2).validationVersion());
        assertEquals(published.validatedAt(), persisted.records().get(2).validatedAt());
        assertEquals("staging/run-publish-notice-b-001.json#recordVersion=4", published.publishRef());
        assertNotNull(published.publishedAt());
    }

    @Test
    void pendingRunsStayUntouchedWithoutPublishOrQuarantine() throws IOException {
        Harness harness = harness();
        RawReceiptV1 raw = pbocRaw("run-publish-pending-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", "CNY/1 USD", "CNY");
        ingest(harness, raw);
        Path staging = harness.root().resolveDataRef(DataPaths.stagingRef(raw.runId()));
        String before = FileDigest.sha256(staging);

        PublishOutcome outcome = harness.publish().process(raw.runId());

        assertEquals(PublishOutcome.PublishAction.NOT_READY, outcome.action());
        assertEquals(ProcessingStage.RECEIVED, outcome.processingStage());
        assertEquals(before, FileDigest.sha256(staging), "a pending run must not change any byte");
        assertFalse(Files.exists(harness.root().path().resolve("quarantine")));
    }

    @Test
    void parsedPendingStaysUntouchedWithoutPublishOrQuarantine() throws IOException {
        Harness harness = harness();
        RawReceiptV1 raw = pbocRaw("run-publish-parsed-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", "CNY/1 USD", "CNY");
        ingest(harness, raw);
        CandidateV1 candidate = new PbocCandidateStandardizer().standardize(raw).candidate();
        harness.timelineStore().append(raw.runId(), new LifecycleSnapshotV1(
                2, ProcessingStage.PARSED, ValidationStatus.PENDING, candidate, null, null, null, null, null,
                RECEIVED_AT.plusMinutes(1)));
        Path staging = harness.root().resolveDataRef(DataPaths.stagingRef(raw.runId()));
        String before = FileDigest.sha256(staging);

        PublishOutcome outcome = harness.publish().process(raw.runId());

        assertEquals(PublishOutcome.PublishAction.NOT_READY, outcome.action());
        assertEquals(ProcessingStage.PARSED, outcome.processingStage());
        assertEquals(before, FileDigest.sha256(staging));
        assertFalse(Files.exists(harness.root().path().resolve("quarantine")));
    }

    @Test
    void validatedRejectedGeneratesQuarantineProjectionWithoutPublishing() throws IOException {
        Harness harness = harness();
        RawReceiptV1 raw = pbocRaw("run-quarantine-unit-001", MonitorSeriesDefaults.EUR_CNY_ITEM_ID,
                "7.8067", "2026-08-10", "CNY/100 EUR", "CNY");
        ingest(harness, raw);
        ValidationOutcome validated = harness.validation().process(raw.runId());
        assertEquals(ValidationStatus.REJECTED, validated.validationStatus());
        Path staging = harness.root().resolveDataRef(DataPaths.stagingRef(raw.runId()));
        String stagingBefore = FileDigest.sha256(staging);

        PublishOutcome outcome = harness.publish().process(raw.runId());

        assertEquals(PublishOutcome.PublishAction.QUARANTINED, outcome.action());
        assertEquals(ValidationStatus.REJECTED, outcome.validationStatus());
        assertNotNull(outcome.quarantineRef());
        assertEquals(DataPaths.quarantineRef(raw.itemId(), raw.receivedAt(), raw.runId()), outcome.quarantineRef());
        Path quarantinePath = harness.root().resolveDataRef(outcome.quarantineRef());
        Path quarantineManifest = harness.root().resolveDataRef(DataPaths.manifestRef(outcome.quarantineRef()));
        assertTrue(Files.isRegularFile(quarantinePath));
        assertTrue(Files.isRegularFile(quarantineManifest));
        assertTrue(ManifestVerifier.matches(harness.root(), outcome.quarantineRef(), quarantinePath,
                quarantineManifest, List.of(raw.runId())));

        QuarantineProjectionV1 projection = JsonV1Codec.decodeFile(Files.readAllBytes(quarantinePath),
                QuarantineProjectionV1.class);
        assertEquals(outcome.quarantineRef(), projection.quarantineRef());
        assertEquals(raw.itemId(), projection.itemId());
        assertEquals(raw.runId(), projection.runId());
        assertEquals(raw.rawRef(), projection.rawRef());
        assertEquals("staging/" + raw.runId() + ".json", projection.stagingRef());
        assertEquals(3, projection.terminalRecordVersion());
        assertEquals(ProcessingStage.VALIDATED, projection.processingStage());
        assertEquals(ValidationStatus.REJECTED, projection.validationStatus());
        assertEquals("UNIT_MISMATCH", projection.reasonCode());
        assertEquals("pboc-basic-validation-v1", projection.validationVersion());
        assertEquals(raw.payloadSha256(), projection.rawPayloadSha256());
        assertEquals(rawFileSha256(harness, raw.rawRef()), projection.rawFileSha256());
        assertEquals(raw.receivedAt(), projection.receivedAt());
        assertEquals(stagingBefore, FileDigest.sha256(staging),
                "quarantine must never modify the timeline");
        LifecycleTimelineV1 timeline = harness.timelineStore().read(raw.runId());
        assertEquals(3, timeline.currentRecordVersion());
        assertFalse(timeline.isPublishedForDailyInput());
    }

    @Test
    void validatedConflictGeneratesQuarantineProjection() throws IOException {
        Harness harness = harness();
        RawReceiptV1 baseline = pbocRaw("run-quarantine-conflict-a-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", "CNY/1 USD", "CNY");
        RawReceiptV1 conflicting = pbocRaw("run-quarantine-conflict-b-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.8000", "2026-08-10", "CNY/1 USD", "CNY");
        ingest(harness, baseline);
        ingest(harness, conflicting);
        harness.validation().process(baseline.runId());
        ValidationOutcome conflict = harness.validation().process(conflicting.runId());
        assertEquals(ValidationStatus.CONFLICT, conflict.validationStatus());

        PublishOutcome outcome = harness.publish().process(conflicting.runId());

        assertEquals(PublishOutcome.PublishAction.QUARANTINED, outcome.action());
        QuarantineProjectionV1 projection = decodeQuarantine(harness, outcome.quarantineRef());
        assertEquals(ValidationStatus.CONFLICT, projection.validationStatus());
        assertEquals("VALUE_CONFLICT", projection.reasonCode());
        assertEquals("pboc-basic-validation-v1", projection.validationVersion());
    }

    @Test
    void receivedRejectedGeneratesQuarantineWithNullValidationVersion() throws IOException {
        Harness harness = harness();
        RawReceiptV1 raw = pbocRaw("run-quarantine-received-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", null, "CNY/1 USD", "CNY");
        ingest(harness, raw);
        ValidationOutcome validated = harness.validation().process(raw.runId());
        assertEquals(ProcessingStage.RECEIVED, validated.processingStage());
        assertEquals(ValidationStatus.REJECTED, validated.validationStatus());

        PublishOutcome outcome = harness.publish().process(raw.runId());

        assertEquals(PublishOutcome.PublishAction.QUARANTINED, outcome.action());
        QuarantineProjectionV1 projection = decodeQuarantine(harness, outcome.quarantineRef());
        assertEquals(ProcessingStage.RECEIVED, projection.processingStage());
        assertEquals(ValidationStatus.REJECTED, projection.validationStatus());
        assertEquals("STANDARDIZATION_FAILED", projection.reasonCode());
        assertNull(projection.validationVersion());
        assertEquals(2, projection.terminalRecordVersion());
    }

    @Test
    void quarantineReplayIsIdempotentAndNeverOverwrites() throws IOException {
        Harness harness = harness();
        RawReceiptV1 raw = pbocRaw("run-quarantine-replay-001", MonitorSeriesDefaults.EUR_CNY_ITEM_ID,
                "7.8067", "2026-08-10", "CNY/100 EUR", "CNY");
        ingest(harness, raw);
        harness.validation().process(raw.runId());
        PublishOutcome first = harness.publish().process(raw.runId());
        Path quarantinePath = harness.root().resolveDataRef(first.quarantineRef());
        String firstHash = FileDigest.sha256(quarantinePath);
        String stagingBefore = FileDigest.sha256(harness.root().resolveDataRef(DataPaths.stagingRef(raw.runId())));

        PublishOutcome replay = harness.publish().process(raw.runId());

        assertEquals(first.quarantineRef(), replay.quarantineRef());
        assertEquals(firstHash, FileDigest.sha256(quarantinePath), "replay must not rewrite the projection");
        assertEquals(stagingBefore, FileDigest.sha256(harness.root().resolveDataRef(DataPaths.stagingRef(raw.runId()))));
    }

    @Test
    void publishedReplayIsNoOp() throws IOException {
        Harness harness = harness();
        RawReceiptV1 raw = pbocRaw("run-publish-replay-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", "CNY/1 USD", "CNY");
        ingest(harness, raw);
        harness.validation().process(raw.runId());
        harness.publish().process(raw.runId());
        Path staging = harness.root().resolveDataRef(DataPaths.stagingRef(raw.runId()));
        String afterPublish = FileDigest.sha256(staging);

        PublishOutcome replay = harness.publish().process(raw.runId());

        assertEquals(PublishOutcome.PublishAction.ALREADY_PUBLISHED, replay.action());
        assertEquals(4, replay.recordVersion());
        assertEquals(afterPublish, FileDigest.sha256(staging), "published replay must not change bytes");
    }

    private Harness harness() {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("d2-t02 publish gate root"));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        new ConfigActivationStore(root, fileStore, FIXED_CLOCK).ensureInitialDefault();
        RawReceiptStore rawStore = new RawReceiptStore(root, fileStore, FIXED_CLOCK);
        TimelineStore timelineStore = new TimelineStore(root, fileStore, FIXED_CLOCK);
        LifecycleValidationService validation =
                new LifecycleValidationService(root, timelineStore, FIXED_CLOCK);
        QuarantineStore quarantineStore = new QuarantineStore(root, fileStore, FIXED_CLOCK);
        LifecyclePublishService publish =
                new LifecyclePublishService(root, timelineStore, quarantineStore, FIXED_CLOCK);
        return new Harness(root, fileStore, rawStore, timelineStore, validation, quarantineStore, publish);
    }

    private static void ingest(Harness harness, RawReceiptV1 raw) {
        harness.rawStore().store(raw);
        harness.timelineStore().createInitial(raw.runId(), raw.rawRef(), raw.receivedAt());
    }

    private static String rawFileSha256(Harness harness, String rawRef) throws IOException {
        return JsonV1Codec.decodeFile(
                Files.readAllBytes(harness.root().resolveDataRef(DataPaths.manifestRef(rawRef))),
                ManifestV1.class).fileSha256();
    }

    private static QuarantineProjectionV1 decodeQuarantine(Harness harness, String quarantineRef) throws IOException {
        return JsonV1Codec.decodeFile(
                Files.readAllBytes(harness.root().resolveDataRef(quarantineRef)), QuarantineProjectionV1.class);
    }

    private static RawReceiptV1 pbocRaw(
            String runId,
            String itemId,
            String value,
            String businessDate,
            String unit,
            String currency
    ) {
        byte[] payload = ("test/contract fixture PBOC-shaped page - NOT REAL PBOC - " + runId)
                .getBytes(StandardCharsets.UTF_8);
        return new RawReceiptV1(
                SchemaV1.VERSION,
                RawReceiptV1.deriveRawRef(Mode.FORMAL, ProviderType.OFFICIAL_WEB, itemId, RECEIVED_AT, runId),
                "acq-" + runId,
                runId,
                Mode.FORMAL,
                ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML,
                1,
                SOURCE_NAME,
                "https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/2026081009013821880/index.html",
                "PBOC公告列表=https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html;公告标题=test fixture",
                itemId,
                businessDate,
                businessDate,
                businessDate == null ? null : "2026-08-10 09:25:38",
                PUBLISHED_AT,
                RECEIVED_AT,
                null,
                value,
                unit,
                currency,
                null,
                200,
                "text/html",
                "base64",
                Base64.getEncoder().encodeToString(payload),
                JsonV1Codec.sha256LowerHex(payload),
                  "1美元对人民币",
                  RECEIVED_AT,
                  null
          );
    }

    private static byte[] fixtureBytes(String name) throws IOException {
        try (InputStream stream = Objects.requireNonNull(
                PublishGateTest.class.getClassLoader().getResourceAsStream(FIXTURE_ROOT + name),
                () -> "Missing D2-T02 contract fixture " + name)) {
            return stream.readAllBytes();
        }
    }

    private record Harness(
            DataRoot root,
            AtomicFileStore fileStore,
            RawReceiptStore rawStore,
            TimelineStore timelineStore,
            LifecycleValidationService validation,
            QuarantineStore quarantineStore,
            LifecyclePublishService publish
    ) {
    }
}
