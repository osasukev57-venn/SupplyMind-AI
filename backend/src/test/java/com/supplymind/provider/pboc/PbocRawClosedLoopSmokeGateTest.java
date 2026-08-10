package com.supplymind.provider.pboc;

import com.supplymind.SupplyMindApplication;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.FileDigest;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.RawConflictEvidenceV1;
import com.supplymind.foundation.storage.RawReceiptConflictException;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.SingleWriterGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PbocRawClosedLoopSmokeGateTest {

    private static final ZoneId SHANGHAI = DataPaths.SHANGHAI;
    private static final Clock SHANGHAI_CLOCK = Clock.system(SHANGHAI);
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final String WRITER_LOCK_REF = "runtime/dirty/.supplymind-writer.lock";
    private static final Pattern USD_ANCHOR = Pattern.compile(
            Pattern.quote("1美元对人民币") + "\\s*([0-9]+(?:\\.[0-9]+)?)\\s*元");
    private static final Pattern EUR_ANCHOR = Pattern.compile(
            Pattern.quote("1欧元对人民币") + "\\s*([0-9]+(?:\\.[0-9]+)?)\\s*元");

    @TempDir
    Path temporaryDirectory;

    @Test
    @EnabledIfSystemProperty(named = "pboc.real-network", matches = "true")
    void realCollectionRepeatTriggerAndRestartReadSmokeGate() throws Exception {
        String rootValue = System.getProperty("d1-t05.data-root");
        assertNotNull(rootValue, "an explicit absolute d1-t05.data-root is required for the real smoke gate");
        Path configuredRoot = Path.of(rootValue).toAbsolutePath().normalize();
        assertTrue(Path.of(rootValue).isAbsolute(), "the real smoke gate dataRoot must be absolute");
        clearDirectory(configuredRoot);

        PbocCollectionResult first = null;
        Map<String, String> afterFirst = null;
        Map<String, String> afterRepeat = null;
        ConfigurableApplicationContext contextA = startContext(configuredRoot);
        try {
            assertEquals(configuredRoot, contextA.getBean(DataRoot.class).path());
            PbocOfficialWebDataProvider provider = contextA.getBean(PbocOfficialWebDataProvider.class);

            first = provider.collectLatestAnnouncement();
            assertRealCollectionResult(first);
            afterFirst = assertRealCollectionFiles(contextA.getBean(DataRoot.class), configuredRoot, first);

            RepeatIdempotencyOutcome repeat = assertFrozenRepeatIdempotency(
                    configuredRoot, first, afterFirst, provider::collectLatestAnnouncement);
            afterRepeat = repeat.afterRepeat();
        } finally {
            contextA.close();
        }
        assertFalse(contextA.isActive(), "context A must be fully closed and have released its beans before restart");
        Objects.requireNonNull(first, "phase 1 must succeed before restart verification");
        Objects.requireNonNull(afterRepeat, "phase 2 must complete before restart verification");

        RestartReadOutcome restart;
        try (ConfigurableApplicationContext contextB = startContext(configuredRoot)) {
            assertNotSame(contextA, contextB,
                    "context B must be a brand-new Spring ApplicationContext, never the closed instance");
            assertTrue(contextB.isActive());
            assertEquals(configuredRoot, contextB.getBean(DataRoot.class).path(),
                    "context B must be started against the same physical dataRoot");
            restart = assertRestartRead(contextB, configuredRoot, first, afterRepeat);
        }

        Map<String, String> beforeFailureAttempt = snapshotDataRoot(configuredRoot);
        FailurePathOutcome failurePath = assertFailurePathAgainstRealRoot(configuredRoot);
        assertEquals(beforeFailureAttempt, snapshotDataRoot(configuredRoot),
                "a disconnected retry must not create, delete or change any persisted byte");

        printAndWriteEvidence(configuredRoot, first, afterFirst, afterRepeat, restart, failurePath);
    }

    @Test
    void networkFailureNeverFabricatesDataOrWritesArtifacts() throws Exception {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("d1-t05 disconnected retry"));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        new ConfigActivationStore(root, fileStore, SHANGHAI_CLOCK).ensureInitialDefault();
        List<PbocDiagnosticEvent> events = new ArrayList<>();
        PbocOfficialWebDataProvider provider = new PbocOfficialWebDataProvider(
                root,
                new RawReceiptStore(root, fileStore, SHANGHAI_CLOCK),
                new com.supplymind.foundation.storage.RawAcquisitionStore(root, fileStore, SHANGHAI_CLOCK),
                fileStore,
                SHANGHAI_CLOCK,
                disconnectedTransport(),
                new PbocAnnouncementParser(),
                events::add
        );

        PbocCollectionException exception = assertThrows(PbocCollectionException.class,
                provider::collectLatestAnnouncement);

        assertEquals(PbocCollectionFailureKind.EXTERNAL_ACCESS_BLOCKED, exception.failureKind());
        assertEquals("HTTP", exception.stage());
        assertFalse(Files.exists(root.path().resolve("raw")), "a disconnected retry must not create a raw directory");
        assertFalse(Files.exists(root.path().resolve("staging")), "a disconnected retry must not create lifecycle files");
        assertFalse(Files.exists(root.path().resolve("runtime/conflicts")),
                "a disconnected retry must not fabricate conflict evidence");
        assertEquals(1, events.size());
        PbocDiagnosticEvent event = events.get(0);
        assertEquals("EXTERNAL_ACCESS_BLOCKED", event.outcome());
        assertEquals("HTTP", event.stage());
        assertEquals(PbocOfficialWebDataProvider.ANNOUNCEMENT_LIST_URI.toString(), event.sanitizedUrl());
        assertNull(event.httpStatus());
        assertNotEquals("NONE", event.exceptionType());
        System.out.printf("D1T05_SMOKE_GATE disconnectedRetry outcome=EXTERNAL_ACCESS_BLOCKED stage=%s url=%s exception=%s java=%s noRawCreated=true%n",
                event.stage(), event.sanitizedUrl(), event.exceptionType(), System.getProperty("java.version"));
    }

    private static ConfigurableApplicationContext startContext(Path dataRoot) {
        return new SpringApplicationBuilder(SupplyMindApplication.class)
                .web(WebApplicationType.NONE)
                .run("--supplymind.data-root=" + dataRoot,
                        "--spring.main.web-application-type=none");
    }

    private static void clearDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            Files.createDirectories(directory);
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            List<Path> children = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path child : children) {
                if (child.equals(directory)) {
                    continue;
                }
                Files.deleteIfExists(child);
            }
        }
        assertTrue(Files.isDirectory(directory) && isEmptyDirectory(directory),
                "the smoke gate dataRoot must be emptied before the real collection");
    }

    private static boolean isEmptyDirectory(Path directory) throws IOException {
        try (Stream<Path> children = Files.list(directory)) {
            return children.findAny().isEmpty();
        }
    }

    private static void assertRealCollectionResult(PbocCollectionResult result) {
        assertNotNull(result.listUrl());
        assertNotNull(result.detailUrl());
        assertEquals("https", result.listUrl().getScheme());
        assertEquals("www.pbc.gov.cn", result.listUrl().getHost());
        assertEquals("www.pbc.gov.cn", result.detailUrl().getHost());
        assertNotEquals(result.listUrl(), result.detailUrl(), "a real detail link must be discovered from the list");
        assertNotNull(result.businessDate());
        assertTrue(SHA256_PATTERN.matcher(result.payloadSha256()).matches());
        RawReceiptV1 usd = result.usdRaw();
        RawReceiptV1 eur = result.eurRaw();
        assertNotNull(usd);
        assertNotNull(eur);
        assertEquals(result.acquisitionId(), usd.acquisitionId());
        assertEquals(result.acquisitionId(), eur.acquisitionId());
        assertEquals(result.payloadSha256(), usd.payloadSha256());
        assertEquals(result.payloadSha256(), eur.payloadSha256());
        assertArrayEquals(Base64.getDecoder().decode(usd.payloadBase64()),
                Base64.getDecoder().decode(eur.payloadBase64()),
                "both currencies must share the identical official response payload");
        assertNotEquals(usd.runId(), eur.runId());
        assertNotEquals(usd.rawRef(), eur.rawRef());
        assertNotEquals(usd.itemId(), eur.itemId());
        assertEquals(MonitorSeriesDefaults.USD_CNY_ITEM_ID, usd.itemId());
        assertEquals(MonitorSeriesDefaults.EUR_CNY_ITEM_ID, eur.itemId());
        assertEquals(result.businessDate(), usd.sourceBusinessDate());
        assertEquals(result.businessDate(), eur.sourceBusinessDate());
        assertInitialTimelineValues(result.usdTimeline(), usd);
        assertInitialTimelineValues(result.eurTimeline(), eur);
    }

    private static Map<String, String> assertRealCollectionFiles(DataRoot dataRoot, Path root, PbocCollectionResult first)
            throws IOException {
        RawReceiptV1 usd = decodeRaw(dataRoot, first.usdRaw().rawRef());
        RawReceiptV1 eur = decodeRaw(dataRoot, first.eurRaw().rawRef());
        assertEquals(first.usdRaw(), usd);
        assertEquals(first.eurRaw(), eur);
        assertRawFileAndManifest(dataRoot, usd, MonitorSeriesDefaults.USD_CNY_ITEM_ID, "CNY/1 USD",
                "1美元对人民币", first.businessDate(), first.detailUrl(), MonitorSeriesDefaults.PBOC_SOURCE_NAME,
                USD_ANCHOR);
        assertRawFileAndManifest(dataRoot, eur, MonitorSeriesDefaults.EUR_CNY_ITEM_ID, "CNY/1 EUR",
                "1欧元对人民币", first.businessDate(), first.detailUrl(), MonitorSeriesDefaults.PBOC_SOURCE_NAME,
                EUR_ANCHOR);
        assertInitialTimelineOnDisk(dataRoot, decodeTimeline(dataRoot, usd.runId()), usd);
        assertInitialTimelineOnDisk(dataRoot, decodeTimeline(dataRoot, eur.runId()), eur);
        return snapshotDataRoot(root);
    }

    private static RestartReadOutcome assertRestartRead(
            ConfigurableApplicationContext contextB,
            Path root,
            PbocCollectionResult first,
            Map<String, String> beforeRestart
    ) throws IOException {
        DataRoot dataRoot = contextB.getBean(DataRoot.class);
        assertEquals(root, dataRoot.path());
        assertNotNull(contextB.getBean(RawReceiptStore.class),
                "context B must expose its own storage beans");
        assertNotNull(contextB.getBean(AtomicFileStore.class),
                "context B must expose its own atomic file store bean");
        assertNotNull(contextB.getBean(ConfigActivationStore.class),
                "context B must expose its own configuration activation bean");
        assertNotNull(contextB.getBean(PbocOfficialWebDataProvider.class),
                "context B must expose its own provider bean");
        assertNotNull(contextB.getBean(SingleWriterGuard.class),
                "context B must re-acquire the single-writer guard on the same dataRoot");

        assertEquals(beforeRestart, snapshotDataRoot(root),
                "restarting the application must not change any persisted byte");
        RawReceiptV1 usd = decodeRaw(dataRoot, first.usdRaw().rawRef());
        RawReceiptV1 eur = decodeRaw(dataRoot, first.eurRaw().rawRef());
        assertEquals(first.usdRaw(), usd, "restart read must return the identical USD raw");
        assertEquals(first.eurRaw(), eur, "restart read must return the identical EUR raw");
        assertRawFileAndManifest(dataRoot, usd, MonitorSeriesDefaults.USD_CNY_ITEM_ID, "CNY/1 USD",
                "1美元对人民币", first.businessDate(), first.detailUrl(), MonitorSeriesDefaults.PBOC_SOURCE_NAME,
                USD_ANCHOR);
        assertRawFileAndManifest(dataRoot, eur, MonitorSeriesDefaults.EUR_CNY_ITEM_ID, "CNY/1 EUR",
                "1欧元对人民币", first.businessDate(), first.detailUrl(), MonitorSeriesDefaults.PBOC_SOURCE_NAME,
                EUR_ANCHOR);
        assertInitialTimelineOnDisk(dataRoot, decodeTimeline(dataRoot, usd.runId()), usd);
        assertInitialTimelineOnDisk(dataRoot, decodeTimeline(dataRoot, eur.runId()), eur);
        System.out.printf("D1T05_SMOKE_GATE restartRead outcome=PASS secondSpringContext=true dataRoot=%s filesUnchanged=true usd=%s eur=%s%n",
                root, usd.rawValue(), eur.rawValue());
        return new RestartReadOutcome(true, root.toString());
    }

    private static RepeatIdempotencyOutcome assertFrozenRepeatIdempotency(
            Path root,
            PbocCollectionResult first,
            Map<String, String> beforeRepeat,
            Supplier<PbocCollectionResult> repeatTrigger
    ) throws IOException {
        DataRoot dataRoot = DataRoot.forTest(root);
        PbocCollectionResult repeated = null;
        PbocCollectionException failure = null;
        try {
            repeated = repeatTrigger.get();
        } catch (PbocCollectionException exception) {
            failure = exception;
        }
        Map<String, String> afterRepeat = snapshotDataRoot(root);
        Map<String, String> added = new TreeMap<>();
        List<String> changed = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        afterRepeat.forEach((ref, hash) -> {
            if (!beforeRepeat.containsKey(ref)) {
                added.put(ref, hash);
            } else if (!beforeRepeat.get(ref).equals(hash)) {
                changed.add(ref);
            }
        });
        beforeRepeat.forEach((ref, hash) -> {
            if (!afterRepeat.containsKey(ref)) {
                deleted.add(ref);
            }
        });
        assertTrue(changed.isEmpty(), "a repeat trigger must never alter existing bytes: " + changed);
        assertTrue(deleted.isEmpty(), "a repeat trigger must never delete files: " + deleted);

        List<Map<String, String>> conflictSummaries = new ArrayList<>();
        for (Map.Entry<String, String> entry : added.entrySet()) {
            String ref = entry.getKey();
            if (ref.endsWith(".manifest.json")) {
                String businessRef = ref.substring(0, ref.length() - ".manifest.json".length());
                assertTrue(added.containsKey(businessRef), "a conflict evidence manifest must accompany its evidence");
                continue;
            }
            assertTrue(ref.startsWith("runtime/conflicts/raw/"),
                    "a repeat trigger must not create files outside the frozen conflict evidence path: " + ref);
            RawConflictEvidenceV1 evidence = decodeConflict(dataRoot, ref);
            assertEquals("1.0", evidence.schemaVersion());
            assertEquals(evidence.incomingReceipt().runId(), evidence.runId());
            assertEquals(evidence.incomingReceipt().rawRef(), evidence.existingRawRef());
            assertEquals(evidence.incomingReceipt().itemId(), evidence.itemId());
            assertEquals(first.businessDate(), evidence.incomingReceipt().sourceBusinessDate(),
                    "the repeat trigger must target the same business date");
            String yearMonth = String.format("%04d-%02d",
                    evidence.incomingReceipt().receivedAt().atZoneSameInstant(SHANGHAI).getYear(),
                    evidence.incomingReceipt().receivedAt().atZoneSameInstant(SHANGHAI).getMonthValue());
            assertTrue(ref.startsWith("runtime/conflicts/raw/" + evidence.itemId() + "/" + yearMonth + "/"
                    + evidence.runId() + "/"), "conflict evidence must use the frozen raw conflict routing: " + ref);
            Path existingRawPath = dataRoot.resolveDataRef(evidence.existingRawRef());
            assertTrue(Files.isRegularFile(existingRawPath));
            assertEquals(FileDigest.sha256(existingRawPath), evidence.existingFileSha256(),
                    "conflict evidence must name the untouched existing raw hash");
            assertEquals(FileDigest.sha256(JsonV1Codec.encodeFile(evidence.incomingReceipt())),
                    evidence.incomingFileSha256(), "conflict evidence must hash its own incoming receipt exactly");
            Path conflictPath = dataRoot.resolveDataRef(ref);
            Path conflictManifest = dataRoot.resolveDataRef(DataPaths.manifestRef(ref));
            assertTrue(Files.isRegularFile(conflictManifest));
            assertTrue(ManifestVerifier.matches(dataRoot, ref, conflictPath, conflictManifest, List.of(evidence.runId())));
            conflictSummaries.add(Map.of(
                    "conflictRef", ref,
                    "conflictId", evidence.conflictId(),
                    "itemId", evidence.itemId(),
                    "runId", evidence.runId(),
                    "existingFileSha256", evidence.existingFileSha256(),
                    "incomingFileSha256", evidence.incomingFileSha256(),
                    "incomingReceivedAt", evidence.incomingReceipt().receivedAt().toString(),
                    "detectedAt", evidence.detectedAt().toString()
            ));
        }

        if (failure != null) {
            assertEquals(PbocCollectionFailureKind.PERSISTENCE_FAILED, failure.failureKind());
            assertTrue(hasCause(failure, RawReceiptConflictException.class),
                    "a different-hash repeat must fail closed with the frozen raw conflict");
            assertFalse(added.isEmpty(),
                    "a real repeat trigger with a fresh receivedAt must leave frozen conflict evidence");
            System.out.printf("D1T05_SMOKE_GATE repeatTrigger outcome=FROZEN_CONFLICT_EVIDENCE failureKind=%s conflicts=%d businessDate=%s%n",
                    failure.failureKind().name(), conflictSummaries.size(), first.businessDate());
        } else {
            assertNotNull(repeated, "a repeat trigger must either return a result or fail closed");
            assertEquals(first.acquisitionId(), repeated.acquisitionId());
            assertEquals(first.businessDate(), repeated.businessDate());
            assertEquals(first.payloadSha256(), repeated.payloadSha256());
            assertEquals(first.usdRaw().runId(), repeated.usdRaw().runId());
            assertEquals(first.eurRaw().runId(), repeated.eurRaw().runId());
            assertTrue(added.isEmpty(), "a byte-identical replay must be a true no-op");
            System.out.printf("D1T05_SMOKE_GATE repeatTrigger outcome=IDEMPOTENT_NO_OP businessDate=%s%n", first.businessDate());
        }
        return new RepeatIdempotencyOutcome(afterRepeat, repeated, failure, conflictSummaries);
    }

    private static FailurePathOutcome assertFailurePathAgainstRealRoot(Path root) {
        DataRoot dataRoot = DataRoot.forTest(root);
        AtomicFileStore fileStore = new AtomicFileStore(dataRoot, new DirtyMarkerCodec());
        List<PbocDiagnosticEvent> events = new ArrayList<>();
        PbocOfficialWebDataProvider provider = new PbocOfficialWebDataProvider(
                dataRoot,
                new RawReceiptStore(dataRoot, fileStore, SHANGHAI_CLOCK),
                new com.supplymind.foundation.storage.RawAcquisitionStore(dataRoot, fileStore, SHANGHAI_CLOCK),
                fileStore,
                SHANGHAI_CLOCK,
                disconnectedTransport(),
                new PbocAnnouncementParser(),
                events::add
        );
        PbocCollectionException exception = assertThrows(PbocCollectionException.class,
                provider::collectLatestAnnouncement);
        assertEquals(PbocCollectionFailureKind.EXTERNAL_ACCESS_BLOCKED, exception.failureKind());
        assertEquals(1, events.size());
        PbocDiagnosticEvent event = events.get(0);
        assertEquals("EXTERNAL_ACCESS_BLOCKED", event.outcome());
        assertEquals(PbocOfficialWebDataProvider.ANNOUNCEMENT_LIST_URI.toString(), event.sanitizedUrl());
        assertFalse(event.sanitizedUrl().contains("?"));
        assertNotEquals("NONE", event.exceptionType());
        System.out.printf("D1T05_SMOKE_GATE failurePath outcome=EXTERNAL_ACCESS_BLOCKED stage=%s url=%s exception=%s httpStatus=%s%n",
                event.stage(), event.sanitizedUrl(), event.exceptionType(), event.httpStatus());
        return new FailurePathOutcome(event.stage(), event.sanitizedUrl(), event.exceptionType(), event.httpStatus());
    }

    private static void assertRawFileAndManifest(
            DataRoot dataRoot,
            RawReceiptV1 raw,
            String expectedItemId,
            String expectedUnit,
            String expectedAnchor,
            String expectedBusinessDate,
            URI detailUrl,
            String expectedSourceName,
            Pattern anchorPattern
    ) throws IOException {
        assertEquals(expectedItemId, raw.itemId());
        assertEquals(expectedAnchor, raw.matchAnchor());
        assertEquals(expectedUnit, raw.rawUnit());
        assertEquals("CNY", raw.rawCurrency());
        assertEquals(expectedSourceName, raw.actualSourceName());
        assertEquals(expectedBusinessDate, raw.sourceBusinessDate());
        assertEquals(detailUrl.toString(), raw.sourceUrl());
        assertEquals(200, raw.httpStatus());
        assertEquals("base64", raw.payloadEncoding());
        assertTrue(raw.contentType().toLowerCase(Locale.ROOT).startsWith("text/html"));
        assertEquals(raw.receivedAt(), raw.updatedAt());
        byte[] payload = Base64.getDecoder().decode(raw.payloadBase64());
        assertEquals(raw.payloadSha256(), FileDigest.sha256(payload));
        assertTrue(new BigDecimal(raw.rawValue()).signum() > 0, "rawValue must be a positive decimal: " + raw.rawValue());
        assertEquals(raw.rawValue(), extractAnchorDecimal(payload, anchorPattern, expectedAnchor),
                "rawValue must equal the anchor value in the retained official page bytes");

        Path rawPath = dataRoot.resolveDataRef(raw.rawRef());
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(raw.rawRef()));
        assertTrue(Files.isRegularFile(rawPath));
        assertTrue(Files.isRegularFile(manifestPath));
        ManifestV1 manifest = decodeManifest(dataRoot, DataPaths.manifestRef(raw.rawRef()));
        assertEquals(rawPath.getFileName().toString(), manifest.fileName());
        assertEquals(FileDigest.sha256(rawPath), manifest.fileSha256());
        assertEquals(Files.size(rawPath), manifest.byteLength());
        assertTrue(manifest.sourceRunIds().contains(raw.runId()));
        assertTrue(ManifestVerifier.matches(dataRoot, raw.rawRef(), rawPath, manifestPath, List.of(raw.runId())));
    }

    private static void assertInitialTimelineOnDisk(DataRoot dataRoot, LifecycleTimelineV1 timeline, RawReceiptV1 raw)
            throws IOException {
        assertInitialTimelineValues(timeline, raw);
        String stagingRef = DataPaths.stagingRef(raw.runId());
        Path stagingPath = dataRoot.resolveDataRef(stagingRef);
        Path stagingManifest = dataRoot.resolveDataRef(DataPaths.manifestRef(stagingRef));
        assertTrue(Files.isRegularFile(stagingPath));
        assertTrue(Files.isRegularFile(stagingManifest));
        assertEquals(timeline, JsonV1Codec.decodeFile(Files.readAllBytes(stagingPath), LifecycleTimelineV1.class));
        assertTrue(ManifestVerifier.matches(dataRoot, stagingRef, stagingPath, stagingManifest, List.of(raw.runId())));
    }

    private static void assertInitialTimelineValues(LifecycleTimelineV1 timeline, RawReceiptV1 raw) {
        assertEquals(raw.runId(), timeline.runId());
        assertEquals(raw.rawRef(), timeline.rawRef());
        assertEquals(1, timeline.currentRecordVersion());
        assertEquals(1, timeline.records().size());
        assertEquals(ProcessingStage.RECEIVED, timeline.current().processingStage());
        assertEquals(ValidationStatus.PENDING, timeline.current().validationStatus());
        assertNull(timeline.current().candidate());
        assertNull(timeline.current().reasonCode());
        assertNull(timeline.current().validationVersion());
        assertNull(timeline.current().validatedAt());
        assertNull(timeline.current().publishedAt());
        assertNull(timeline.current().publishRef());
        assertFalse(timeline.isPublishedForDailyInput());
    }

    private static String extractAnchorDecimal(byte[] payload, Pattern pattern, String anchor) {
        String visibleText = new String(payload, StandardCharsets.UTF_8)
                .replaceAll("(?is)<!--.*?-->", " ")
                .replaceAll("(?is)<(script|style)\\b.*?</\\1>", " ")
                .replaceAll("(?is)<[^>]+>", " ")
                .replaceAll("\\s+", " ");
        Matcher matcher = pattern.matcher(visibleText);
        List<String> values = new ArrayList<>();
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        assertEquals(1, values.size(), "the official page visible text must contain exactly one " + anchor + " anchor");
        return values.get(0);
    }

    private static RawReceiptV1 decodeRaw(DataRoot dataRoot, String ref) throws IOException {
        return JsonV1Codec.decodeFile(Files.readAllBytes(dataRoot.resolveDataRef(ref)), RawReceiptV1.class);
    }

    private static LifecycleTimelineV1 decodeTimeline(DataRoot dataRoot, String runId) throws IOException {
        return JsonV1Codec.decodeFile(Files.readAllBytes(dataRoot.resolveDataRef(DataPaths.stagingRef(runId))),
                LifecycleTimelineV1.class);
    }

    private static ManifestV1 decodeManifest(DataRoot dataRoot, String manifestRef) throws IOException {
        return JsonV1Codec.decodeFile(Files.readAllBytes(dataRoot.resolveDataRef(manifestRef)), ManifestV1.class);
    }

    private static RawConflictEvidenceV1 decodeConflict(DataRoot dataRoot, String ref) throws IOException {
        return JsonV1Codec.decodeFile(Files.readAllBytes(dataRoot.resolveDataRef(ref)), RawConflictEvidenceV1.class);
    }

    private static Map<String, String> snapshotDataRoot(Path root) {
        TreeMap<String, String> snapshot = new TreeMap<>();
        if (!Files.isDirectory(root)) {
            return snapshot;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> !root.relativize(path).toString().replace('\\', '/').equals(WRITER_LOCK_REF))
                    .forEach(path ->
                            snapshot.put(root.relativize(path).toString().replace('\\', '/'), FileDigest.sha256(path)));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to snapshot " + root, exception);
        }
        return snapshot;
    }

    private static JdkPbocHttpTransport disconnectedTransport() {
        HttpClient client = HttpClient.newBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", 1)))
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new JdkPbocHttpTransport(client);
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

    private static void printAndWriteEvidence(
            Path root,
            PbocCollectionResult first,
            Map<String, String> afterFirst,
            Map<String, String> afterRepeat,
            RestartReadOutcome restart,
            FailurePathOutcome failurePath
    ) throws IOException {
        System.out.printf("D1T05_SMOKE_GATE firstCollection outcome=SUCCESS businessDate=%s list=%s detail=%s payloadSha256=%s java=%s springBoot=%s%n",
                first.businessDate(), sanitize(first.listUrl()), sanitize(first.detailUrl()), first.payloadSha256(),
                System.getProperty("java.version"), SpringBootVersion.getVersion());
        System.out.printf("D1T05_SMOKE_GATE usd runId=%s rawValue=%s unit=%s rawFileSha256=%s stagingFileSha256=%s%n",
                first.usdRaw().runId(), first.usdRaw().rawValue(), first.usdRaw().rawUnit(),
                sha256OfRef(root, first.usdRaw().rawRef()), sha256OfRef(root, DataPaths.stagingRef(first.usdRaw().runId())));
        System.out.printf("D1T05_SMOKE_GATE eur runId=%s rawValue=%s unit=%s rawFileSha256=%s stagingFileSha256=%s%n",
                first.eurRaw().runId(), first.eurRaw().rawValue(), first.eurRaw().rawUnit(),
                sha256OfRef(root, first.eurRaw().rawRef()), sha256OfRef(root, DataPaths.stagingRef(first.eurRaw().runId())));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("gate", "D1-T05 PBOC dual-currency raw closed-loop smoke gate");
        summary.put("executedAt", OffsetDateTime.now(SHANGHAI_CLOCK).withNano(0).toString());
        summary.put("javaRuntime", System.getProperty("java.version"));
        summary.put("springBootVersion", SpringBootVersion.getVersion());
        summary.put("dataRoot", root.toString());
        summary.put("announcementListUrl", first.listUrl().toString());
        summary.put("detailUrl", first.detailUrl().toString());
        summary.put("businessDate", first.businessDate());
        summary.put("acquisitionId", first.acquisitionId());
        summary.put("payloadSha256", first.payloadSha256());
        summary.put("usd", receiptSummary(root, first.usdRaw()));
        summary.put("eur", receiptSummary(root, first.eurRaw()));

        Map<String, String> repeat = new LinkedHashMap<>();
        boolean conflictBranch = repeatOutcomeIsConflict(afterFirst, afterRepeat);
        if (conflictBranch) {
            repeat.put("outcome", "FROZEN_CONFLICT_EVIDENCE");
            repeat.put("conflictRefs", conflictRefsOf(afterFirst, afterRepeat).toString());
        } else {
            repeat.put("outcome", "IDEMPOTENT_NO_OP");
        }
        summary.put("repeatTrigger", repeat);
        summary.put("restartRead", Map.of(
                "outcome", "PASS",
                "secondSpringContext", true,
                "distinctFromContextA", true,
                "contextAClosedBeforeRestart", true,
                "contextBDataRoot", restart.contextBDataRoot(),
                "filesUnchanged", restart.filesUnchanged(),
                "beansResolvedFromContextB",
                "DataRoot,RawReceiptStore,AtomicFileStore,ConfigActivationStore,PbocOfficialWebDataProvider,SingleWriterGuard"));
        summary.put("disconnectedRetry", Map.of(
                "outcome", "EXTERNAL_ACCESS_BLOCKED",
                "stage", failurePath.stage(),
                "sanitizedUrl", failurePath.sanitizedUrl(),
                "exceptionType", failurePath.exceptionType(),
                "httpStatus", String.valueOf(failurePath.httpStatus())));

        String evidenceDirValue = System.getProperty("d1-t05.evidence-dir");
        if (evidenceDirValue != null && !evidenceDirValue.isBlank()) {
            Path evidenceDir = Path.of(evidenceDirValue).toAbsolutePath().normalize();
            Files.createDirectories(evidenceDir);
            byte[] bytes = JsonV1Codec.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(summary);
            Files.write(evidenceDir.resolve("d1-t05-smoke-gate-summary.json"), bytes);
        }
    }

    private static Map<String, Object> receiptSummary(Path root, RawReceiptV1 raw) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("itemId", raw.itemId());
        value.put("runId", raw.runId());
        value.put("rawRef", raw.rawRef());
        value.put("rawValue", raw.rawValue());
        value.put("rawUnit", raw.rawUnit());
        value.put("rawCurrency", raw.rawCurrency());
        value.put("matchAnchor", raw.matchAnchor());
        value.put("actualSourceName", raw.actualSourceName());
        value.put("sourceUrl", raw.sourceUrl());
        value.put("sourceBusinessDateRaw", raw.sourceBusinessDateRaw());
        value.put("sourceBusinessDate", raw.sourceBusinessDate());
        value.put("sourcePublishedAtRaw", raw.sourcePublishedAtRaw());
        value.put("sourcePublishedAt", raw.sourcePublishedAt().toString());
        value.put("receivedAt", raw.receivedAt().toString());
        value.put("httpStatus", raw.httpStatus());
        value.put("contentType", raw.contentType());
        value.put("payloadEncoding", raw.payloadEncoding());
        value.put("payloadSha256", raw.payloadSha256());
        value.put("rawFileSha256", sha256OfRef(root, raw.rawRef()));
        value.put("rawManifestFileSha256", sha256OfRef(root, DataPaths.manifestRef(raw.rawRef())));
        value.put("stagingFileSha256", sha256OfRef(root, DataPaths.stagingRef(raw.runId())));
        return value;
    }

    private static String sha256OfRef(Path root, String ref) {
        return FileDigest.sha256(DataRoot.forTest(root).resolveDataRef(ref));
    }

    private static boolean repeatOutcomeIsConflict(Map<String, String> afterFirst, Map<String, String> afterRepeat) {
        for (String ref : afterRepeat.keySet()) {
            if (!afterFirst.containsKey(ref) && !ref.endsWith(".manifest.json")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> conflictRefsOf(Map<String, String> before, Map<String, String> after) {
        List<String> refs = new ArrayList<>();
        after.forEach((ref, hash) -> {
            if (!before.containsKey(ref) && !ref.endsWith(".manifest.json")) {
                refs.add(ref);
            }
        });
        return refs;
    }

    private static String sanitize(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            return "unavailable";
        }
        String path = uri.getRawPath();
        return uri.getScheme() + "://" + uri.getHost() + (path == null || path.isBlank() ? "/" : path);
    }

    private record RepeatIdempotencyOutcome(
            Map<String, String> afterRepeat,
            PbocCollectionResult repeated,
            PbocCollectionException failure,
            List<Map<String, String>> conflictSummaries
    ) {
    }

    private record RestartReadOutcome(boolean filesUnchanged, String contextBDataRoot) {
    }

    private record FailurePathOutcome(String stage, String sanitizedUrl, String exceptionType, Integer httpStatus) {
    }
}
