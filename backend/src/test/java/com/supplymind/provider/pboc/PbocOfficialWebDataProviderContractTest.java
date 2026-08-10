package com.supplymind.provider.pboc;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.RawReceiptStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthetic fixture contract tests only. They do not perform real PBOC network access and cannot prove D1-T04 or AT-SRC-002.
 */
class PbocOfficialWebDataProviderContractTest {

    private static final String FIXTURE_ROOT = "contracts/v1/d1-t04-pboc/";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-10T01:30:00Z"), ZoneOffset.UTC);
    private static final URI LIST_URI = PbocOfficialWebDataProvider.ANNOUNCEMENT_LIST_URI;
    private static final URI DETAIL_URI = URI.create(
            "https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/fixture-announcement-20260810.html");
    private static final String EXPECTED_SOURCE_NAME = "中国人民银行官网（授权中国外汇交易中心公布）";

    @TempDir
    Path temporaryDirectory;

    @Test
    void collectsSyntheticDualCurrencyFixtureIntoIndependentRawAndReceivedPendingTimelines() throws Exception {
        byte[] listEntity = fixtureBytes("announcement-list-normal.html");
        byte[] detailEntity = fixtureBytes("announcement-detail-normal.html");
        FixtureTransport transport = successfulTransport(listEntity, detailEntity);
        TestHarness harness = initializedHarness(transport);

        PbocCollectionResult result = harness.provider().collectLatestAnnouncement();

        String expectedPayloadSha256 = independentSha256(detailEntity);
        assertEquals("2026-08-10", result.businessDate());
        assertEquals(LIST_URI, result.listUrl());
        assertEquals(DETAIL_URI, result.detailUrl());
        assertEquals(expectedPayloadSha256, result.payloadSha256());
        assertEquals(2, transport.requestedUris().size());
        assertEquals(List.of(LIST_URI, DETAIL_URI), transport.requestedUris());

        RawReceiptV1 usd = result.usdRaw();
        RawReceiptV1 eur = result.eurRaw();
        assertEquals(result.acquisitionId(), usd.acquisitionId());
        assertEquals(result.acquisitionId(), eur.acquisitionId());
        assertEquals(expectedPayloadSha256, usd.payloadSha256());
        assertEquals(expectedPayloadSha256, eur.payloadSha256());
        assertArrayEquals(detailEntity, Base64.getDecoder().decode(usd.payloadBase64()));
        assertArrayEquals(detailEntity, Base64.getDecoder().decode(eur.payloadBase64()));
        assertNotEquals(usd.runId(), eur.runId());
        assertNotEquals(usd.rawRef(), eur.rawRef());
        assertNotEquals(usd.itemId(), eur.itemId());

        assertRawTraceability(usd, "FX.USD.CNY.PBOC_MID", "1美元对人民币", "CNY/1 USD", "6.812345678");
        assertRawTraceability(eur, "FX.EUR.CNY.PBOC_MID", "1欧元对人民币", "CNY/1 EUR", "7.901234567");
        assertSourceAcquisitionTraceability(harness.root(), detailEntity, usd, eur);

        assertInitialTimeline(harness.root(), result.usdTimeline(), usd);
        assertInitialTimeline(harness.root(), result.eurTimeline(), eur);
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(usd.rawRef())));
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(eur.rawRef())));
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(DataPaths.manifestRef(usd.rawRef()))));
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(DataPaths.manifestRef(eur.rawRef()))));
        assertEquals(1, harness.events().size());
        assertEquals("SUCCESS", harness.events().get(0).outcome());
        assertEquals("COMPLETE", harness.events().get(0).stage());
    }

    @Test
    void rejectsMissingUsdFixturePersistingOnlyTheSourceRawAcquisition() throws Exception {
        byte[] detailEntity = fixtureBytes("announcement-detail-missing-usd.html");
        TestHarness harness = initializedHarness(successfulTransport(
                fixtureBytes("announcement-list-normal.html"), detailEntity));

        PbocCollectionException exception = assertThrows(PbocCollectionException.class,
                () -> harness.provider().collectLatestAnnouncement());

        assertEquals(PbocCollectionFailureKind.PARSE_REJECTED, exception.failureKind());
        assertEquals("DETAIL", exception.stage());
        assertEquals(DETAIL_URI, exception.uri());
        assertAcquisitionOnlyWasCreated(harness.root(), detailEntity, exception);
    }

    @Test
    void rejectsMissingEurFixturePersistingOnlyTheSourceRawAcquisition() throws Exception {
        byte[] detailEntity = fixtureBytes("announcement-detail-missing-eur.html");
        TestHarness harness = initializedHarness(successfulTransport(
                fixtureBytes("announcement-list-normal.html"), detailEntity));

        PbocCollectionException exception = assertThrows(PbocCollectionException.class,
                () -> harness.provider().collectLatestAnnouncement());

        assertEquals(PbocCollectionFailureKind.PARSE_REJECTED, exception.failureKind());
        assertEquals("DETAIL", exception.stage());
        assertEquals(DETAIL_URI, exception.uri());
        assertAcquisitionOnlyWasCreated(harness.root(), detailEntity, exception);
    }

    @Test
    void rejectsStructureChangedFixturePersistingOnlyTheSourceRawAcquisition() throws Exception {
        byte[] detailEntity = fixtureBytes("announcement-detail-structure-changed.html");
        TestHarness harness = initializedHarness(successfulTransport(
                fixtureBytes("announcement-list-normal.html"), detailEntity));

        PbocCollectionException exception = assertThrows(PbocCollectionException.class,
                () -> harness.provider().collectLatestAnnouncement());

        assertEquals(PbocCollectionFailureKind.PARSE_REJECTED, exception.failureKind());
        assertEquals("DETAIL", exception.stage());
        assertEquals(DETAIL_URI, exception.uri());
        assertAcquisitionOnlyWasCreated(harness.root(), detailEntity, exception);
    }

    @Test
    void recordsSanitizedExternalAccessBlockedDiagnosticWithoutQueryTokenOrResponseEntity() throws Exception {
        URI exposedUri = URI.create(LIST_URI + "?access_token=do-not-log&trace=fixture-secret");
        PbocCollectionException timeout = new PbocCollectionException(
                PbocCollectionFailureKind.EXTERNAL_ACCESS_BLOCKED,
                "HTTP",
                exposedUri,
                null,
                "synthetic timeout only",
                new HttpTimeoutException("fixture timeout")
        );
        FixtureTransport transport = FixtureTransport.failure(timeout);
        TestHarness harness = initializedHarness(transport);

        PbocCollectionException exception = assertThrows(PbocCollectionException.class,
                () -> harness.provider().collectLatestAnnouncement());

        assertEquals(PbocCollectionFailureKind.EXTERNAL_ACCESS_BLOCKED, exception.failureKind());
        assertEquals("HTTP", exception.stage());
        assertEquals(1, transport.requestedUris().size());
        assertEquals(List.of(LIST_URI), transport.requestedUris());
        assertEquals(1, harness.events().size());
        PbocDiagnosticEvent event = harness.events().get(0);
        assertEquals("EXTERNAL_ACCESS_BLOCKED", event.outcome());
        assertEquals("HTTP", event.stage());
        assertEquals(LIST_URI.toString(), event.sanitizedUrl());
        assertNull(event.httpStatus());
        assertEquals(HttpTimeoutException.class.getSimpleName(), event.exceptionType());
        String eventText = event.toString();
        assertFalse(eventText.contains("?"));
        assertFalse(eventText.contains("access_token"));
        assertFalse(eventText.contains("do-not-log"));
        assertFalse(eventText.contains("fixture-secret"));
        assertFalse(eventText.contains("announcement-detail-normal"));
        assertNoRawOrTimelineWasCreated(harness.root());
    }

    @Test
    void replaysAnIdenticalResponseIdempotentlyWithoutChangingRawOrInitialTimelineBytes() throws Exception {
        byte[] listEntity = fixtureBytes("announcement-list-normal.html");
        byte[] detailEntity = fixtureBytes("announcement-detail-normal.html");
        FixtureTransport transport = successfulTransport(listEntity, detailEntity);
        TestHarness harness = initializedHarness(transport);

        PbocCollectionResult first = harness.provider().collectLatestAnnouncement();
        byte[] usdRawBefore = Files.readAllBytes(harness.root().resolveDataRef(first.usdRaw().rawRef()));
        byte[] eurRawBefore = Files.readAllBytes(harness.root().resolveDataRef(first.eurRaw().rawRef()));
        byte[] usdTimelineBefore = Files.readAllBytes(harness.root().resolveDataRef(DataPaths.stagingRef(first.usdRaw().runId())));
        byte[] eurTimelineBefore = Files.readAllBytes(harness.root().resolveDataRef(DataPaths.stagingRef(first.eurRaw().runId())));

        PbocCollectionResult replay = harness.provider().collectLatestAnnouncement();

        assertEquals(first, replay);
        assertEquals(4, transport.requestedUris().size());
        assertArrayEquals(usdRawBefore, Files.readAllBytes(harness.root().resolveDataRef(first.usdRaw().rawRef())));
        assertArrayEquals(eurRawBefore, Files.readAllBytes(harness.root().resolveDataRef(first.eurRaw().rawRef())));
        assertArrayEquals(usdTimelineBefore,
                Files.readAllBytes(harness.root().resolveDataRef(DataPaths.stagingRef(first.usdRaw().runId()))));
        assertArrayEquals(eurTimelineBefore,
                Files.readAllBytes(harness.root().resolveDataRef(DataPaths.stagingRef(first.eurRaw().runId()))));
        assertEquals(1, acquisitionFileCount(harness.root()),
                "an identical replay must not create a duplicate source acquisition");
        assertEquals(2, itemRawCount(harness.root()),
                "an identical replay must not create duplicate item raws");
        assertEquals(2, harness.events().size());
        assertTrue(harness.events().stream().allMatch(event -> "SUCCESS".equals(event.outcome())));
    }

    private static int acquisitionFileCount(DataRoot root) throws IOException {
        Path sourceDir = root.path().resolve("raw").resolve("source");
        if (!Files.isDirectory(sourceDir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(sourceDir)) {
            return (int) stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().endsWith(".manifest.json"))
                    .count();
        }
    }

    private static int itemRawCount(DataRoot root) throws IOException {
        Path rawFormalDir = root.path().resolve("raw").resolve("formal");
        if (!Files.isDirectory(rawFormalDir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.walk(rawFormalDir)) {
            return (int) stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().endsWith(".manifest.json"))
                    .count();
        }
    }

    private TestHarness initializedHarness(FixtureTransport transport) {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("D1-T04 synthetic test data"));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        new ConfigActivationStore(root, fileStore, FIXED_CLOCK).ensureInitialDefault();
        RawReceiptStore rawReceiptStore = new RawReceiptStore(root, fileStore, FIXED_CLOCK);
        List<PbocDiagnosticEvent> events = new ArrayList<>();
        PbocOfficialWebDataProvider provider = new PbocOfficialWebDataProvider(
                root,
                rawReceiptStore,
                new com.supplymind.foundation.storage.RawAcquisitionStore(root, fileStore, FIXED_CLOCK),
                fileStore,
                FIXED_CLOCK,
                transport,
                new PbocAnnouncementParser(),
                events::add
        );
        return new TestHarness(root, provider, events);
    }

    private static FixtureTransport successfulTransport(byte[] listEntity, byte[] detailEntity) {
        return FixtureTransport.responses(Map.of(
                LIST_URI, new PbocHttpResponse(LIST_URI, 200, "text/html; charset=UTF-8", listEntity),
                DETAIL_URI, new PbocHttpResponse(DETAIL_URI, 200, "text/html; charset=UTF-8", detailEntity)
        ));
    }

    private static void assertSourceAcquisitionTraceability(
            DataRoot root, byte[] detailEntity, RawReceiptV1 usd, RawReceiptV1 eur
    ) throws IOException {
        assertEquals(usd.acquisitionRef(), eur.acquisitionRef(),
                "both item receipts must reference the same source acquisition");
        assertEquals(DataPaths.acquisitionRef(usd.acquisitionId()), usd.acquisitionRef());
        Path acquisitionPath = root.resolveDataRef(usd.acquisitionRef());
        assertTrue(Files.isRegularFile(acquisitionPath), "the source raw acquisition must be persisted");
        assertTrue(Files.isRegularFile(root.resolveDataRef(DataPaths.manifestRef(usd.acquisitionRef()))));
        com.supplymind.foundation.model.RawAcquisitionV1 acquisition = JsonV1Codec.decodeFile(
                Files.readAllBytes(acquisitionPath), com.supplymind.foundation.model.RawAcquisitionV1.class);
        assertEquals(independentSha256(detailEntity), acquisition.payloadSha256(),
                "the acquisition must carry the unparsed HTTP payload hash");
        assertArrayEquals(detailEntity, Base64.getDecoder().decode(acquisition.payloadBase64()),
                "the acquisition must retain the full original response bytes");
        assertEquals(DETAIL_URI.toString(), acquisition.detailUrl());
        assertEquals(200, acquisition.httpStatus());
        assertEquals(usd.payloadSha256(), acquisition.payloadSha256());
        assertTrue(ManifestVerifier.matches(root, usd.acquisitionRef(), acquisitionPath,
                root.resolveDataRef(DataPaths.manifestRef(usd.acquisitionRef())),
                List.of(acquisition.acquisitionId())));
        Path rawFormalDir = root.path().resolve("raw").resolve("formal");
        try (Stream<Path> stream = Files.walk(rawFormalDir)) {
            assertTrue(stream.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".json"))
                            .filter(path -> !path.getFileName().toString().endsWith(".manifest.json"))
                            .count() == 2,
                    "the formal chain must hold exactly the two item-level raws after the source acquisition");
        }
    }

    private static void assertRawTraceability(
            RawReceiptV1 raw,
            String itemId,
            String sourceFieldKey,
            String unit,
            String originalValue
    ) {
        assertEquals(itemId, raw.itemId());
        assertEquals(DETAIL_URI.toString(), raw.sourceUrl());
        assertTrue(raw.sourceReference().contains(LIST_URI.toString()));
        assertEquals(EXPECTED_SOURCE_NAME, raw.actualSourceName());
        assertEquals("2026-08-10", raw.sourceBusinessDate());
        assertEquals("2026年8月10日", raw.sourceBusinessDateRaw());
        assertEquals("2026-08-10 09:25:38", raw.sourcePublishedAtRaw());
        assertEquals("CNY", raw.rawCurrency());
        assertEquals(unit, raw.rawUnit());
        assertEquals(originalValue, raw.rawValue());
        assertEquals(sourceFieldKey, raw.matchAnchor());
        assertEquals(200, raw.httpStatus());
        assertEquals("text/html; charset=UTF-8", raw.contentType());
    }

    private static void assertInitialTimeline(DataRoot root, LifecycleTimelineV1 timeline, RawReceiptV1 raw) throws IOException {
        assertEquals(raw.runId(), timeline.runId());
        assertEquals(raw.rawRef(), timeline.rawRef());
        assertEquals(1, timeline.currentRecordVersion());
        assertEquals(1, timeline.records().size());
        assertEquals(ProcessingStage.RECEIVED, timeline.current().processingStage());
        assertEquals(ValidationStatus.PENDING, timeline.current().validationStatus());
        assertNull(timeline.current().candidate());
        assertFalse(timeline.isPublishedForDailyInput());

        Path stagingPath = root.resolveDataRef(DataPaths.stagingRef(raw.runId()));
        assertTrue(Files.isRegularFile(stagingPath));
        assertEquals(timeline, JsonV1Codec.decodeFile(Files.readAllBytes(stagingPath), LifecycleTimelineV1.class));
        assertTrue(Files.isRegularFile(root.resolveDataRef(DataPaths.manifestRef(DataPaths.stagingRef(raw.runId())))));
    }

    private static void assertNoRawOrTimelineWasCreated(DataRoot root) {
        assertFalse(Files.exists(root.path().resolve("raw")), "a failed request must not create a raw directory");
        assertFalse(Files.exists(root.path().resolve("staging")), "failed collection must not create a lifecycle directory");
    }

    /**
     * DEC-056 raw-first contract on the failure path: the source-level raw acquisition of the
     * HTTP detail response must already be on disk with a verifiable manifest, while no item
     * raw, timeline or downstream state exists.
     */
    private static void assertAcquisitionOnlyWasCreated(DataRoot root, byte[] detailEntity,
                                                        PbocCollectionException exception) throws IOException {
        Path sourceDir = root.path().resolve("raw").resolve("source");
        assertTrue(Files.isDirectory(sourceDir), "the source raw acquisition directory must exist");
        List<Path> acquisitionFiles;
        try (Stream<Path> stream = Files.list(sourceDir)) {
            acquisitionFiles = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().endsWith(".manifest.json"))
                    .toList();
        }
        assertEquals(1, acquisitionFiles.size(),
                "parse failure must leave exactly one source raw acquisition");
        Path acquisitionPath = acquisitionFiles.get(0);
        String acquisitionRef = "raw/source/" + acquisitionPath.getFileName();
        com.supplymind.foundation.model.RawAcquisitionV1 acquisition =
                JsonV1Codec.decodeFile(Files.readAllBytes(acquisitionPath),
                        com.supplymind.foundation.model.RawAcquisitionV1.class);
        assertEquals(independentSha256(detailEntity), acquisition.payloadSha256(),
                "the persisted acquisition must carry the unparsed HTTP payload hash");
        assertArrayEquals(detailEntity, Base64.getDecoder().decode(acquisition.payloadBase64()),
                "the persisted acquisition must retain the full original response bytes");
        assertEquals(200, acquisition.httpStatus());
        assertTrue(ManifestVerifier.matches(root, acquisitionRef, acquisitionPath,
                root.resolveDataRef(DataPaths.manifestRef(acquisitionRef)),
                List.of(acquisition.acquisitionId())),
                "the persisted acquisition manifest must verify");
        assertFalse(Files.exists(root.path().resolve("raw").resolve("formal")),
                "parse failure must not create item-level raws");
        assertFalse(Files.exists(root.path().resolve("staging")),
                "parse failure must not create lifecycle timelines");
        assertNotNull(exception);
    }

    private byte[] fixtureBytes(String name) throws IOException {
        try (InputStream stream = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(FIXTURE_ROOT + name),
                () -> "Missing D1-T04 synthetic fixture " + name)) {
            return stream.readAllBytes();
        }
    }

    private static String independentSha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("JDK SHA-256 must be available", exception);
        }
    }

    private record TestHarness(
            DataRoot root,
            PbocOfficialWebDataProvider provider,
            List<PbocDiagnosticEvent> events
    ) {
    }

    private static final class FixtureTransport implements PbocHttpTransport {
        private final Map<URI, PbocHttpResponse> responses;
        private final PbocCollectionException failure;
        private final List<URI> requestedUris = new ArrayList<>();

        private FixtureTransport(Map<URI, PbocHttpResponse> responses, PbocCollectionException failure) {
            this.responses = responses;
            this.failure = failure;
        }

        static FixtureTransport responses(Map<URI, PbocHttpResponse> responses) {
            return new FixtureTransport(Map.copyOf(responses), null);
        }

        static FixtureTransport failure(PbocCollectionException failure) {
            return new FixtureTransport(Map.of(), failure);
        }

        @Override
        public PbocHttpResponse get(URI uri) {
            requestedUris.add(uri);
            if (failure != null) {
                throw failure;
            }
            PbocHttpResponse response = responses.get(uri);
            if (response == null) {
                throw new AssertionError("Unexpected synthetic fixture request: " + uri);
            }
            return response;
        }

        List<URI> requestedUris() {
            return List.copyOf(requestedUris);
        }
    }
}
