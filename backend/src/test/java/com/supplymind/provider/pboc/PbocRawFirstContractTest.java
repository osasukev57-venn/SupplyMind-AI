package com.supplymind.provider.pboc;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.RawAcquisitionV1;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.FileDigest;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.RawAcquisitionStore;
import com.supplymind.foundation.storage.RawReceiptConflictException;
import com.supplymind.foundation.storage.RawReceiptStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DEC-056 deterministic raw-first and idempotency contract tests (no real network):
 * 1) a repeat with the same stable business key and the same official payload is an
 *    IDEMPOTENT REPLAY even when receivedAt differs;
 * 2) the same business key with a different official payload is a CONFLICT that preserves the
 *    original formal raw, commits frozen conflict evidence and fails closed before any
 *    downstream stage.
 */
class PbocRawFirstContractTest {

    private static final String FIXTURE_ROOT = "contracts/v1/d1-t04-pboc/";
    private static final URI LIST_URI = PbocOfficialWebDataProvider.ANNOUNCEMENT_LIST_URI;
    private static final URI DETAIL_URI = URI.create(
            "https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/fixture-announcement-20260810.html");

    @TempDir
    Path temporaryDirectory;

    @Test
    void sameBusinessKeyAndSamePayloadAreIdempotentAcrossDifferentReceivedAt() throws Exception {
        byte[] listEntity = fixtureBytes("announcement-list-normal.html");
        byte[] detailEntity = fixtureBytes("announcement-detail-normal.html");
        DataRoot root = initializedRoot();
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        RawReceiptStore rawStore = new RawReceiptStore(root, fileStore, Clock.systemUTC());
        RawAcquisitionStore acquisitionStore = new RawAcquisitionStore(root, fileStore, Clock.systemUTC());
        List<PbocDiagnosticEvent> events = new ArrayList<>();

        PbocOfficialWebDataProvider providerAtT1 = provider(root, rawStore, acquisitionStore, fileStore,
                Clock.fixed(Instant.parse("2026-08-10T01:30:00Z"), ZoneOffset.UTC),
                transport(listEntity, detailEntity), events);
        PbocCollectionResult first = providerAtT1.collectLatestAnnouncement();
        Map<String, String> before = snapshot(root);

        PbocOfficialWebDataProvider providerAtT2 = provider(root, rawStore, acquisitionStore, fileStore,
                Clock.fixed(Instant.parse("2026-08-10T03:00:00Z"), ZoneOffset.UTC),
                transport(listEntity, detailEntity), events);
        PbocCollectionResult replay = providerAtT2.collectLatestAnnouncement();

        assertEquals(first, replay,
                "a same-payload replay must return the identical formal refs and receipts");
        assertEquals(first.businessDate(), replay.businessDate());
        assertEquals(first.acquisitionId(), replay.acquisitionId());
        assertEquals(first.payloadSha256(), replay.payloadSha256());
        assertEquals(first.usdRaw().runId(), replay.usdRaw().runId());
        assertEquals(first.eurRaw().runId(), replay.eurRaw().runId());
        assertEquals(first.usdRaw().rawRef(), replay.usdRaw().rawRef());
        assertEquals(first.eurRaw().rawRef(), replay.eurRaw().rawRef());
        assertEquals(before, snapshot(root),
                "an idempotent replay must not add, delete or change any persisted byte");
        assertEquals(1, acquisitionCount(root), "an idempotent replay must not duplicate the acquisition");
        assertEquals(2, itemRawCount(root), "an idempotent replay must not duplicate item raws");
        assertTrue(events.stream().allMatch(event -> "SUCCESS".equals(event.outcome())),
                "an idempotent replay must not raise any conflict");
    }

    @Test
    void sameBusinessKeyWithDifferentPayloadFailsClosedKeepingOriginalRawAndEvidence() throws Exception {
        byte[] listEntity = fixtureBytes("announcement-list-normal.html");
        byte[] originalDetail = fixtureBytes("announcement-detail-normal.html");
        byte[] changedDetail = fixtureBytes("announcement-detail-changed-values.html");
        DataRoot root = initializedRoot();
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        RawReceiptStore rawStore = new RawReceiptStore(root, fileStore, Clock.systemUTC());
        RawAcquisitionStore acquisitionStore = new RawAcquisitionStore(root, fileStore, Clock.systemUTC());
        List<PbocDiagnosticEvent> events = new ArrayList<>();

        PbocOfficialWebDataProvider firstProvider = provider(root, rawStore, acquisitionStore, fileStore,
                Clock.fixed(Instant.parse("2026-08-10T01:30:00Z"), ZoneOffset.UTC),
                transport(listEntity, originalDetail), events);
        PbocCollectionResult first = firstProvider.collectLatestAnnouncement();
        Path originalUsdRawPath = root.resolveDataRef(first.usdRaw().rawRef());
        Path originalEurRawPath = root.resolveDataRef(first.eurRaw().rawRef());
        byte[] originalUsdBytes = Files.readAllBytes(originalUsdRawPath);
        byte[] originalEurBytes = Files.readAllBytes(originalEurRawPath);
        Map<String, String> beforeConflict = snapshot(root);

        PbocOfficialWebDataProvider conflictingProvider = provider(root, rawStore, acquisitionStore, fileStore,
                Clock.fixed(Instant.parse("2026-08-10T03:00:00Z"), ZoneOffset.UTC),
                transport(listEntity, changedDetail), events);
        PbocCollectionException failure = assertThrows(PbocCollectionException.class,
                conflictingProvider::collectLatestAnnouncement);

        assertEquals(PbocCollectionFailureKind.PERSISTENCE_FAILED, failure.failureKind());
        assertTrue(hasCause(failure, RawReceiptConflictException.class),
                "a different-payload repeat must surface the frozen business-key conflict");
        assertArrayEquals(originalUsdBytes, Files.readAllBytes(originalUsdRawPath),
                "the original formal USD raw must never be overwritten");
        assertArrayEquals(originalEurBytes, Files.readAllBytes(originalEurRawPath),
                "the original formal EUR raw must never be overwritten");
        assertEquals(2, itemRawCount(root),
                "a conflicting replay must not create any additional item raw");
        Map<String, String> afterConflict = snapshot(root);
        List<String> added = new ArrayList<>();
        afterConflict.forEach((ref, hash) -> {
            if (!beforeConflict.containsKey(ref) && !ref.endsWith(".manifest.json")) {
                added.add(ref);
            }
        });
        List<String> conflicts = added.stream()
                .filter(ref -> ref.startsWith("runtime/conflicts/raw/"))
                .toList();
        List<String> acquisitions = added.stream()
                .filter(ref -> ref.startsWith("raw/source/"))
                .toList();
        assertEquals(1, conflicts.size(),
                "the conflicting replay must leave exactly one frozen conflict evidence: " + added);
        assertEquals(1, acquisitions.size(),
                "the conflicting observation must itself be preserved as a source acquisition (raw-first): " + added);
        assertTrue(ManifestVerifier.matches(root, conflicts.get(0),
                        root.resolveDataRef(conflicts.get(0)),
                        root.resolveDataRef(DataPaths.manifestRef(conflicts.get(0))),
                        List.of(JsonV1Codec.decodeFile(Files.readAllBytes(root.resolveDataRef(conflicts.get(0))),
                                com.supplymind.foundation.storage.RawConflictEvidenceV1.class).runId())),
                "the conflict evidence manifest must verify");
        assertEquals(2, stagingCount(root),
                "a conflicting replay must not create new lifecycle timelines");
        assertFalse(events.isEmpty());
        assertEquals("PERSISTENCE_FAILED", events.get(events.size() - 1).outcome());
    }

    @Test
    void acquisitionCarriesNoParsedItemFieldsAndIsIdempotentOnSameAcquisitionId() throws Exception {
        byte[] listEntity = fixtureBytes("announcement-list-normal.html");
        byte[] detailEntity = fixtureBytes("announcement-detail-normal.html");
        DataRoot root = initializedRoot();
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        RawReceiptStore rawStore = new RawReceiptStore(root, fileStore, Clock.systemUTC());
        RawAcquisitionStore acquisitionStore = new RawAcquisitionStore(root, fileStore, Clock.systemUTC());
        PbocOfficialWebDataProvider provider = provider(root, rawStore, acquisitionStore, fileStore,
                Clock.fixed(Instant.parse("2026-08-10T01:30:00Z"), ZoneOffset.UTC),
                transport(listEntity, detailEntity), new ArrayList<>());

        PbocCollectionResult first = provider.collectLatestAnnouncement();
        Path acquisitionPath = root.resolveDataRef(first.usdRaw().acquisitionRef());
        byte[] acquisitionBefore = Files.readAllBytes(acquisitionPath);
        RawAcquisitionV1 acquisition = JsonV1Codec.decodeFile(acquisitionBefore, RawAcquisitionV1.class);
        assertNullField(acquisition);
        assertEquals(first.payloadSha256(), acquisition.payloadSha256());

        provider.collectLatestAnnouncement();
        assertArrayEquals(acquisitionBefore, Files.readAllBytes(acquisitionPath),
                "re-storing the same acquisitionId must never rewrite the acquisition bytes");
        assertEquals(1, acquisitionCount(root));
    }

    private static void assertNullField(RawAcquisitionV1 acquisition) {
        assertNotNull(acquisition);
        assertEquals("1.0", acquisition.schemaVersion());
    }

    private DataRoot initializedRoot() throws IOException {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("d2-t05 raw-first contract " + System.nanoTime()));
        AtomicMoveSupport.probeOrFail(root);
        new ConfigActivationStore(root, new AtomicFileStore(root, new DirtyMarkerCodec()), Clock.systemUTC())
                .ensureInitialDefault();
        return root;
    }

    private static PbocOfficialWebDataProvider provider(
            DataRoot root,
            RawReceiptStore rawStore,
            RawAcquisitionStore acquisitionStore,
            AtomicFileStore fileStore,
            Clock clock,
            FixtureTransport transport,
            List<PbocDiagnosticEvent> events
    ) {
        return new PbocOfficialWebDataProvider(root, rawStore, acquisitionStore, fileStore, clock, transport,
                new PbocAnnouncementParser(), events::add);
    }

    private static FixtureTransport transport(byte[] listEntity, byte[] detailEntity) {
        return FixtureTransport.responses(Map.of(
                LIST_URI, new PbocHttpResponse(LIST_URI, 200, "text/html; charset=UTF-8", listEntity),
                DETAIL_URI, new PbocHttpResponse(DETAIL_URI, 200, "text/html; charset=UTF-8", detailEntity)
        ));
    }

    private static int acquisitionCount(DataRoot root) throws IOException {
        Path dir = root.resolveInternalRelative("raw/source");
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return (int) stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().endsWith(".manifest.json"))
                    .count();
        }
    }

    private static int itemRawCount(DataRoot root) throws IOException {
        Path dir = root.resolveInternalRelative("raw/formal");
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return (int) stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().endsWith(".manifest.json"))
                    .count();
        }
    }

    private static int stagingCount(DataRoot root) throws IOException {
        Path dir = root.resolveInternalRelative("staging");
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return (int) stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().endsWith(".manifest.json"))
                    .count();
        }
    }

    private static Map<String, String> snapshot(DataRoot root) {
        java.util.TreeMap<String, String> snapshot = new java.util.TreeMap<>();
        try (Stream<Path> walk = Files.walk(root.path())) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> !root.path().relativize(path).toString().replace('\\', '/')
                            .equals("runtime/dirty/.supplymind-writer.lock"))
                    .forEach(path -> snapshot.put(
                            root.path().relativize(path).toString().replace('\\', '/'), FileDigest.sha256(path)));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to snapshot " + root.path(), exception);
        }
        return snapshot;
    }

    private static boolean hasCause(Throwable throwable, Class<?> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static byte[] fixtureBytes(String name) throws IOException {
        try (InputStream stream = Objects.requireNonNull(
                PbocRawFirstContractTest.class.getClassLoader().getResourceAsStream(FIXTURE_ROOT + name),
                () -> "Missing D2-T05 raw-first fixture " + name)) {
            return stream.readAllBytes();
        }
    }

    private static final class FixtureTransport implements PbocHttpTransport {
        private final Map<URI, PbocHttpResponse> responses;
        private final List<URI> requestedUris = new ArrayList<>();

        private FixtureTransport(Map<URI, PbocHttpResponse> responses) {
            this.responses = responses;
        }

        static FixtureTransport responses(Map<URI, PbocHttpResponse> responses) {
            return new FixtureTransport(Map.copyOf(responses));
        }

        @Override
        public PbocHttpResponse get(URI uri) {
            requestedUris.add(uri);
            PbocHttpResponse response = responses.get(uri);
            if (response == null) {
                throw new AssertionError("Unexpected fixture request: " + uri);
            }
            return response;
        }
    }
}
