package com.supplymind.publish;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.CandidateV1;
import com.supplymind.foundation.model.LifecycleSnapshotV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.SchemaValidationException;
import com.supplymind.foundation.model.SchemaV1;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.QuarantineStore;
import com.supplymind.foundation.storage.RawAcquisitionStore;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.validation.LifecycleValidationService;
import com.supplymind.validation.PbocCandidateStandardizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishedQueryServiceTest {

    private static final ZoneId SHANGHAI = DataPaths.SHANGHAI;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-10T01:02:00Z"), SHANGHAI);
    private static final Clock VALIDATION_CLOCK_0720 = Clock.fixed(Instant.parse("2026-07-20T01:02:00Z"), SHANGHAI);
    private static final Clock VALIDATION_CLOCK_0710 = Clock.fixed(Instant.parse("2026-07-10T01:02:00Z"), SHANGHAI);
    private static final OffsetDateTime RECEIVED_AT = OffsetDateTime.ofInstant(
            Instant.parse("2026-08-10T01:00:00Z"), SHANGHAI);
    private static final OffsetDateTime PUBLISHED_AT = OffsetDateTime.parse("2026-08-10T09:25:38+08:00");
    private static final String SOURCE_NAME = MonitorSeriesDefaults.PBOC_SOURCE_NAME;
    private static final LocalDate REFERENCE_DATE = LocalDate.parse("2026-08-10");

    @TempDir
    Path temporaryDirectory;

    @Test
    void pendingRejectedConflictAndNotYetPublishedAreInvisibleAtBusinessEntry() throws IOException {
        Harness harness = harness();
        RawReceiptV1 received = pbocRaw("run-query-received-001", "6.7904", "2026-08-10");
        RawReceiptV1 parsed = pbocRaw("run-query-parsed-001", "6.7904", "2026-08-10");
        RawReceiptV1 rejected = pbocRaw("run-query-rejected-001", "7.8067", "2026-08-10", "CNY/100 EUR");
        RawReceiptV1 validNotPublished = pbocRaw("run-query-unpublished-001", "6.7904", "2026-08-10");
        ingest(harness, received);
        ingest(harness, parsed);
        ingest(harness, rejected);
        ingest(harness, validNotPublished);
        harness.timelineStore().append(parsed.runId(), new LifecycleSnapshotV1(
                2, ProcessingStage.PARSED, ValidationStatus.PENDING,
                new PbocCandidateStandardizer().standardize(parsed).candidate(), null, null, null, null, null,
                RECEIVED_AT.plusMinutes(1)));
        harness.validation().process(rejected.runId());
        harness.validation().process(validNotPublished.runId());

        assertTrue(harness.query().findPublished(MonitorSeriesDefaults.USD_CNY_ITEM_ID, REFERENCE_DATE).isEmpty());
        assertTrue(harness.query().findPublished(MonitorSeriesDefaults.EUR_CNY_ITEM_ID, REFERENCE_DATE).isEmpty());
        assertNull(harness.query().latestPublished(MonitorSeriesDefaults.USD_CNY_ITEM_ID));
        assertNull(harness.query().latestPublished(MonitorSeriesDefaults.EUR_CNY_ITEM_ID));
    }

    @Test
    void onlyPublishedVerifiedIsVisibleWithFullTraceability() throws IOException {
        Harness harness = harness();
        RawReceiptV1 raw = pbocRaw("run-query-published-001", "6.7904", "2026-08-10");
        ingest(harness, raw);
        harness.validation().process(raw.runId());
        assertTrue(harness.query().findPublished(raw.itemId(), REFERENCE_DATE).isEmpty());

        harness.publish().process(raw.runId());

        List<PublishedRecord> records =
                harness.query().findPublished(raw.itemId(), REFERENCE_DATE);
        assertEquals(1, records.size());
        PublishedRecord record = records.get(0);
        assertEquals(raw.itemId(), record.itemId());
        assertEquals("2026-08-10", record.businessDate());
        assertEquals("6.7904", record.value());
        assertEquals("CNY", record.currency());
        assertEquals("CNY/1 USD", record.unit());
        assertEquals(ProviderType.OFFICIAL_WEB, record.providerType());
        assertEquals(SOURCE_NAME, record.actualSourceName());
        assertEquals(AccessMethod.PUBLIC_OFFICIAL_HTML, record.accessMethod());
        assertEquals("pboc-basic-validation-v1", record.validationVersion());
        assertNotNull(record.validatedAt());
        assertNotNull(record.publishedAt());
        assertNotNull(record.publishRef());
        assertEquals(harness.timelineStore().read(raw.runId()).current().publishRef(), record.publishRef(),
                "PublishedRecord.publishRef must equal the PUBLISHED snapshot publishRef");
        assertEquals("staging/run-query-published-001.json#recordVersion=4", record.publishRef());
        assertEquals(raw.runId(), record.runId());
        assertEquals(raw.rawRef(), record.rawRef());
        assertEquals(4, record.recordVersion());
        assertEquals(raw.payloadSha256(), record.rawPayloadSha256());
        assertEquals(rawFileSha256(harness, raw.rawRef()), record.rawFileSha256());
        assertFalse(record.stale(), "a same-day published value must not be stale");
    }

    @Test
    void latestPublishedReturnsNewestWithStaleInfo() throws IOException {
        Harness harness = harness(Clock.fixed(Instant.parse("2026-08-11T01:02:00Z"), SHANGHAI));
        RawReceiptV1 older = pbocRaw("run-query-latest-old-001", "6.7904", "2026-07-11");
        RawReceiptV1 newest = pbocRaw("run-query-latest-new-001", "6.7904", "2026-08-10");
        ingest(harness, older);
        ingest(harness, newest);
        harness.validation().process(older.runId());
        harness.validation().process(newest.runId());
        harness.publish().process(older.runId());
        harness.publish().process(newest.runId());

        PublishedRecord latest = harness.query().latestPublished(newest.itemId());

        assertNotNull(latest);
        assertEquals("2026-08-10", latest.businessDate());
        assertFalse(latest.stale(), "a business date one day before the reference date is not stale");
        PublishedRecord olderRecord =
                harness.query().findPublished(newest.itemId(), LocalDate.parse("2026-07-11")).get(0);
        assertTrue(olderRecord.stale(), "a business date 31 days before the reference date is stale");
    }

    @Test
    void staleAtReferenceDateIsFalse() throws IOException {
        assertStale(harness(FIXED_CLOCK, FIXED_CLOCK), "2026-08-10", RECEIVED_AT, false,
                "age 0 (same day) is not stale");
    }

    @Test
    void staleOneDayBeforeReferenceIsFalse() throws IOException {
        assertStale(harness(FIXED_CLOCK, FIXED_CLOCK), "2026-08-09", RECEIVED_AT, false,
                "age 1 is not stale");
    }

    @Test
    void staleTwentyNineDaysBeforeReferenceIsFalse() throws IOException {
        assertStale(harness(FIXED_CLOCK, FIXED_CLOCK), "2026-07-12", RECEIVED_AT, false,
                "age 29 is not stale");
    }

    @Test
    void staleExactlyThirtyDaysBeforeReferenceIsFalse() throws IOException {
        assertStale(harness(FIXED_CLOCK, FIXED_CLOCK), "2026-07-11", RECEIVED_AT, false,
                "age 30 is not stale (DEC-051 boundary)");
    }

    @Test
    void staleThirtyOneDaysBeforeReferenceIsTrue() throws IOException {
        assertStale(harness(VALIDATION_CLOCK_0720, FIXED_CLOCK), "2026-07-10",
                OffsetDateTime.ofInstant(Instant.parse("2026-07-20T01:00:00Z"), SHANGHAI), true,
                "age 31 is stale (DEC-051 boundary)");
    }

    @Test
    void staleFarBeyondThirtyDaysIsTrue() throws IOException {
        assertStale(harness(VALIDATION_CLOCK_0710, FIXED_CLOCK), "2026-06-19",
                OffsetDateTime.ofInstant(Instant.parse("2026-07-10T01:00:00Z"), SHANGHAI), true,
                "age 52 is stale");
    }

    private static void assertStale(
            Harness harness,
            String businessDate,
            OffsetDateTime receivedAt,
            boolean expected,
            String message
    ) throws IOException {
        String runId = "run-query-window-" + businessDate.replace("-", "") + "-001";
        RawReceiptV1 raw = pbocRaw(runId, "6.7904", businessDate, "CNY/1 USD", receivedAt);
        ingest(harness, raw);
        harness.validation().process(raw.runId());
        harness.publish().process(raw.runId());
        PublishedRecord record =
                harness.query().findPublished(raw.itemId(), LocalDate.parse(businessDate)).get(0);
        assertEquals(expected, record.stale(), message + " (businessDate=" + businessDate + ")");
    }

    @Test
    void missingPublishRefFailsClosedAtModelLevel() throws IOException {
        Harness harness = harness();
        RawReceiptV1 raw = pbocRaw("run-query-publishref-001", "6.7904", "2026-08-10");
        CandidateV1 candidate = new PbocCandidateStandardizer().standardize(raw).candidate();
        OffsetDateTime at = RECEIVED_AT.plusMinutes(2);

        assertThrows(SchemaValidationException.class, () -> new LifecycleSnapshotV1(
                4, ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED, candidate, null,
                "pboc-basic-validation-v1", at, at, null, at),
                "a PUBLISHED snapshot without publishRef must fail closed");
    }

    @Test
    void publishedVerifiedWithNoticeIsVisible() throws IOException {
        Harness harness = harness();
        RawReceiptV1 first = pbocRaw("run-query-notice-a-001", "6.7904", "2026-08-10");
        RawReceiptV1 duplicate = pbocRaw("run-query-notice-b-001", "6.7904", "2026-08-10");
        ingest(harness, first);
        ingest(harness, duplicate);
        harness.validation().process(first.runId());
        harness.validation().process(duplicate.runId());
        harness.publish().process(first.runId());
        harness.publish().process(duplicate.runId());

        List<PublishedRecord> records = harness.query().findPublished(duplicate.itemId(), REFERENCE_DATE);

        assertEquals(2, records.size(), "both observations of the same business key are visible after publish");
        assertTrue(records.stream().anyMatch(record -> record.runId().equals(duplicate.runId())));
        assertEquals(ValidationStatus.VERIFIED_WITH_NOTICE.name(),
                harness.timelineStore().read(duplicate.runId()).current().validationStatus().name());
    }

    private Harness harness() {
        return harness(FIXED_CLOCK, FIXED_CLOCK);
    }

    private Harness harness(Clock queryClock) {
        return harness(FIXED_CLOCK, queryClock);
    }

    private Harness harness(Clock validationClock, Clock queryClock) {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("d2-t02 query root"));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        new ConfigActivationStore(root, fileStore, FIXED_CLOCK).ensureInitialDefault();
        RawReceiptStore rawStore = new RawReceiptStore(root, fileStore, FIXED_CLOCK);
        TimelineStore timelineStore = new TimelineStore(root, fileStore, FIXED_CLOCK);
        LifecycleValidationService validation =
                new LifecycleValidationService(root, timelineStore, validationClock);
        QuarantineStore quarantineStore = new QuarantineStore(root, fileStore, FIXED_CLOCK);
        LifecyclePublishService publish =
                new LifecyclePublishService(root, timelineStore, quarantineStore, validationClock);
        PublishedQueryService query = new PublishedQueryService(root, timelineStore, queryClock);
        return new Harness(root, fileStore, rawStore, timelineStore, validation, publish, query);
    }

    private static void ingest(Harness harness, RawReceiptV1 raw) {
        new RawAcquisitionStore(harness.root(), harness.fileStore(), FIXED_CLOCK)
                .store(com.supplymind.foundation.model.DomainFixtures.acquisitionFor(raw));
        harness.rawStore().store(raw);
        harness.timelineStore().createInitial(raw.runId(), raw.rawRef(), raw.receivedAt());
    }

    private static String rawFileSha256(Harness harness, String rawRef) throws IOException {
        return JsonV1Codec.decodeFile(
                Files.readAllBytes(harness.root().resolveDataRef(DataPaths.manifestRef(rawRef))),
                ManifestV1.class).fileSha256();
    }

    private static RawReceiptV1 pbocRaw(String runId, String value, String businessDate) {
        return pbocRaw(runId, value, businessDate, "CNY/1 USD");
    }

    private static RawReceiptV1 pbocRaw(String runId, String value, String businessDate, String unit) {
        return pbocRaw(runId, value, businessDate, unit, RECEIVED_AT);
    }

    private static RawReceiptV1 pbocRaw(
            String runId,
            String value,
            String businessDate,
            String unit,
            OffsetDateTime receivedAt
    ) {
        byte[] payload = ("test/contract fixture PBOC-shaped page - NOT REAL PBOC - " + runId)
                .getBytes(StandardCharsets.UTF_8);
        return new RawReceiptV1(
                SchemaV1.VERSION,
                RawReceiptV1.deriveRawRef(Mode.FORMAL, ProviderType.OFFICIAL_WEB,
                        MonitorSeriesDefaults.USD_CNY_ITEM_ID, receivedAt, runId),
                "acq-" + runId,
                runId,
                Mode.FORMAL,
                ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML,
                1,
                SOURCE_NAME,
                "https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/2026081009013821880/index.html",
                "PBOC公告列表=https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html;公告标题=test fixture",
                MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                businessDate,
                businessDate,
                "2026-08-10 09:25:38",
                PUBLISHED_AT,
                receivedAt,
                null,
                value,
                unit,
                "CNY",
                null,
                200,
                "text/html",
                "base64",
                Base64.getEncoder().encodeToString(payload),
                JsonV1Codec.sha256LowerHex(payload),
                "1美元对人民币",
                receivedAt,
                DataPaths.acquisitionRef("acq-" + runId),
                null
        );
    }

    private record Harness(
            DataRoot root,
            AtomicFileStore fileStore,
            RawReceiptStore rawStore,
            TimelineStore timelineStore,
            LifecycleValidationService validation,
            LifecyclePublishService publish,
            PublishedQueryService query
    ) {
    }
}
