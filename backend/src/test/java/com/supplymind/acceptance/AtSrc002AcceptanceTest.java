package com.supplymind.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplymind.SupplyMindApplication;
import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AggregateGrain;
import com.supplymind.foundation.model.AggregateRecordV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.FileDigest;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.RawReceiptConflictException;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.processing.AggregateReadService;
import com.supplymind.publish.PublishedQueryService;
import com.supplymind.publish.PublishedRecord;
import com.supplymind.provider.pboc.PbocCollectionException;
import com.supplymind.provider.pboc.PbocCollectionFailureKind;
import com.supplymind.provider.pboc.PbocOfficialWebDataProvider;
import com.supplymind.scheduling.PbocDay2CollectionService;
import com.supplymind.scheduling.Day2CycleResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AT-SRC-002 formal run (gated: -Dat-src-002.real=true). Executes the frozen acceptance test:
 * real PBOC dual-currency collection -> raw JSON -> standardization/validation -> publish gate
 * -> daily CSV -> four-grain aggregate CSV -> repeat-trigger idempotency -> full restart with
 * read-only offline history/aggregate reads and independent recomputation. Requires an empty
 * absolute data root (-Dat-src-002.data-root) and an optional evidence dir
 * (-Dat-src-002.evidence-dir). Never substitutes fixtures for the official source.
 */
class AtSrc002AcceptanceTest {

    private static final ZoneId SHANGHAI = DataPaths.SHANGHAI;
    private static final String WRITER_LOCK_REF = "runtime/dirty/.supplymind-writer.lock";

    @Test
    @EnabledIfSystemProperty(named = "at-src-002.real", matches = "true")
    void dualCurrencyRealChainIdempotencyAndOfflineRestart() throws Exception {
        String rootValue = System.getProperty("at-src-002.data-root");
        assertNotNull(rootValue, "an explicit absolute at-src-002.data-root is required");
        Path configuredRoot = Path.of(rootValue).toAbsolutePath().normalize();
        assertTrue(Path.of(rootValue).isAbsolute(), "the AT-SRC-002 dataRoot must be absolute");
        clearDirectory(configuredRoot);

        PhaseAOutcome phaseA = runPhaseA(configuredRoot);
        PhaseBOutcome phaseB = runPhaseB(configuredRoot, phaseA);

        writeEvidence(configuredRoot, phaseA, phaseB);
    }

    private PhaseAOutcome runPhaseA(Path configuredRoot) throws IOException {
        ConfigurableApplicationContext contextA = startContext(configuredRoot);
        PhaseAOutcome outcome;
        try {
            DataRoot dataRoot = contextA.getBean(DataRoot.class);
            assertEquals(configuredRoot, dataRoot.path());
            PbocDay2CollectionService orchestration = contextA.getBean(PbocDay2CollectionService.class);
            PbocOfficialWebDataProvider provider = contextA.getBean(PbocOfficialWebDataProvider.class);
            TimelineStore timelineStore = contextA.getBean(TimelineStore.class);
            RawReceiptStore rawStore = contextA.getBean(RawReceiptStore.class);
            PublishedQueryService publishedQuery = contextA.getBean(PublishedQueryService.class);
            AtomicFileStore fileStore = contextA.getBean(AtomicFileStore.class);
            assertNotNull(rawStore);
            assertNotNull(fileStore);

            Day2CycleResult cycle = orchestration.runImmediateCycle();
            assertNotNull(cycle);
            assertEquals(1, cycle.usdDailyRowCount(), "both currencies must reach daily persistence (1 row per business date)");
            assertEquals(1, cycle.eurDailyRowCount(), "both currencies must reach daily persistence (1 row per business date)");
            assertEquals(4, cycle.usdAggregateRefs().size(), "USD must persist all four aggregate grains");
            assertEquals(4, cycle.eurAggregateRefs().size(), "EUR must persist all four aggregate grains");
            LocalDate businessDate = LocalDate.parse(cycle.businessDate());

            RawReceiptV1 usdRaw = decodeRaw(dataRoot, cycle.usdRawRef());
            RawReceiptV1 eurRaw = decodeRaw(dataRoot, cycle.eurRawRef());
            assertRealRawLayer(dataRoot, cycle, usdRaw, "USD");
            assertRealRawLayer(dataRoot, cycle, eurRaw, "EUR");
            assertNotNull(usdRaw.sourceUrl(), "the real capture must retain the official detail URL");
            assertTrue(usdRaw.sourceUrl().startsWith("https://www.pbc.gov.cn/"));

            LifecycleTimelineV1 usdTimeline = decodeTimeline(dataRoot, cycle.usdRunId());
            LifecycleTimelineV1 eurTimeline = decodeTimeline(dataRoot, cycle.eurRunId());
            assertPublishedGate(usdTimeline, cycle.usdRunId());
            assertPublishedGate(eurTimeline, cycle.eurRunId());
            OffsetDateTime usdPublishedAt = usdTimeline.current().publishedAt();
            OffsetDateTime eurPublishedAt = eurTimeline.current().publishedAt();
            String usdPublishRef = usdTimeline.current().publishRef();
            assertNotNull(usdPublishedAt);
            assertNotNull(eurPublishedAt);
            assertNotNull(usdPublishRef);

            List<PublishedRecord> usdPublished = publishedQuery.findPublished(
                    MonitorSeriesDefaults.USD_CNY_ITEM_ID, businessDate);
            List<PublishedRecord> eurPublished = publishedQuery.findPublished(
                    MonitorSeriesDefaults.EUR_CNY_ITEM_ID, businessDate);
            assertEquals(1, usdPublished.size(), "exactly one PUBLISHED+VERIFIED USD record must be queryable");
            assertEquals(1, eurPublished.size(), "exactly one PUBLISHED+VERIFIED EUR record must be queryable");
            assertEquals(cycle.usdRunId(), usdPublished.get(0).runId());
            assertEquals(usdPublishRef, usdPublished.get(0).publishRef());

            DailyRecordV1 usdDailyRow = readSingleDailyRow(dataRoot, cycle.usdDailyRef());
            DailyRecordV1 eurDailyRow = readSingleDailyRow(dataRoot, cycle.eurDailyRef());
            assertDailyRow(cycle, usdDailyRow, usdRaw, usdPublishedAt, "USD");
            assertDailyRow(cycle, eurDailyRow, eurRaw, eurPublishedAt, "EUR");

            Map<String, AggregateRecordV1> usdAggregates = readAggregateGrains(dataRoot, MonitorSeriesDefaults.USD_CNY_ITEM_ID);
            Map<String, AggregateRecordV1> eurAggregates = readAggregateGrains(dataRoot, MonitorSeriesDefaults.EUR_CNY_ITEM_ID);
            String expectedUsd = expectedAvg(usdRaw.rawValue());
            String expectedEur = expectedAvg(eurRaw.rawValue());
            for (AggregateGrain grain : List.of(AggregateGrain.MONTH, AggregateGrain.QUARTER,
                    AggregateGrain.HALFYEAR, AggregateGrain.YEAR)) {
                assertAggregateGrain(dataRoot, cycle, MonitorSeriesDefaults.USD_CNY_ITEM_ID, grain,
                        expectedUsd, usdDailyRow, usdAggregates);
                assertAggregateGrain(dataRoot, cycle, MonitorSeriesDefaults.EUR_CNY_ITEM_ID, grain,
                        expectedEur, eurDailyRow, eurAggregates);
            }

            Map<String, String> beforeRepeat = snapshotDataRoot(configuredRoot);
            PbocCollectionException repeatFailure = assertThrows(PbocCollectionException.class,
                    orchestration::collectRepeatForSameBusinessDate,
                    "a repeat trigger for the same business date must fail closed at the raw layer");
            assertEquals(PbocCollectionFailureKind.PERSISTENCE_FAILED, repeatFailure.failureKind());
            assertTrue(hasCause(repeatFailure, RawReceiptConflictException.class),
                    "the repeat trigger must surface the frozen raw conflict");
            Map<String, String> afterRepeat = snapshotDataRoot(configuredRoot);
            assertRepeatOnlyAddsFrozenConflictEvidence(beforeRepeat, afterRepeat);
            assertEquals(beforeRepeat.get(cycle.usdDailyRef()), afterRepeat.get(cycle.usdDailyRef()),
                    "the repeat trigger must not rewrite the persisted USD daily CSV");
            assertEquals(beforeRepeat.get(cycle.eurDailyRef()), afterRepeat.get(cycle.eurDailyRef()),
                    "the repeat trigger must not rewrite the persisted EUR daily CSV");
            for (String ref : cycle.usdAggregateRefs()) {
                assertEquals(beforeRepeat.get(ref), afterRepeat.get(ref),
                        "the repeat trigger must not rewrite USD aggregate " + ref);
            }
            assertEquals(beforeRepeat.get(DataPaths.stagingRef(cycle.usdRunId())),
                    afterRepeat.get(DataPaths.stagingRef(cycle.usdRunId())),
                    "the repeat trigger must not re-publish (timeline bytes unchanged)");

            outcome = new PhaseAOutcome(
                    cycle, usdRaw, eurRaw, usdTimeline, eurTimeline,
                    usdDailyRow, eurDailyRow, usdAggregates, eurAggregates,
                    usdPublished.get(0), eurPublished.get(0), afterRepeat);
        } finally {
            contextA.close();
        }
        assertFalse(contextA.isActive(), "context A must be fully closed before the restart");
        return outcome;
    }

    private PhaseBOutcome runPhaseB(Path configuredRoot, PhaseAOutcome phaseA) throws IOException {
        try (ConfigurableApplicationContext contextB = startContext(configuredRoot)) {
            assertTrue(contextB.isActive());
            DataRoot dataRoot = contextB.getBean(DataRoot.class);
            assertEquals(configuredRoot, dataRoot.path(),
                    "context B must be started against the same physical dataRoot");
            PublishedQueryService publishedQuery = contextB.getBean(PublishedQueryService.class);
            assertNotNull(contextB.getBean(RawReceiptStore.class), "context B must expose its own storage beans");
            assertNotNull(contextB.getBean(TimelineStore.class));

            LocalDate businessDate = LocalDate.parse(phaseA.cycle().businessDate());
            List<PublishedRecord> usdPublished = publishedQuery.findPublished(
                    MonitorSeriesDefaults.USD_CNY_ITEM_ID, businessDate);
            List<PublishedRecord> eurPublished = publishedQuery.findPublished(
                    MonitorSeriesDefaults.EUR_CNY_ITEM_ID, businessDate);
            assertEquals(1, usdPublished.size(), "restart must read the persisted USD published record offline");
            assertEquals(1, eurPublished.size(), "restart must read the persisted EUR published record offline");
            assertEquals(phaseA.usdPublished().publishRef(), usdPublished.get(0).publishRef());
            assertEquals(phaseA.usdPublished().runId(), usdPublished.get(0).runId());
            assertEquals(phaseA.eurPublished().publishRef(), eurPublished.get(0).publishRef());

            DailyRecordV1 usdDaily = readSingleDailyRow(dataRoot, phaseA.cycle().usdDailyRef());
            DailyRecordV1 eurDaily = readSingleDailyRow(dataRoot, phaseA.cycle().eurDailyRef());
            assertEquals(phaseA.usdDailyRow(), usdDaily, "restart daily decode must be identical");
            assertEquals(phaseA.eurDailyRow(), eurDaily, "restart daily decode must be identical");

            AggregateReadService aggregateReader = new AggregateReadService(dataRoot);
            Map<String, AggregateRecordV1> usdRestart = readAggregateGrainsViaReader(
                    aggregateReader, MonitorSeriesDefaults.USD_CNY_ITEM_ID);
            Map<String, AggregateRecordV1> eurRestart = readAggregateGrainsViaReader(
                    aggregateReader, MonitorSeriesDefaults.EUR_CNY_ITEM_ID);
            for (AggregateGrain grain : List.of(AggregateGrain.MONTH, AggregateGrain.QUARTER,
                    AggregateGrain.HALFYEAR, AggregateGrain.YEAR)) {
                assertEquals(phaseA.usdAggregates().get(grain.wireValue()),
                        usdRestart.get(grain.wireValue()), "restart USD " + grain.wireValue() + " decode must be identical");
                assertEquals(phaseA.eurAggregates().get(grain.wireValue()),
                        eurRestart.get(grain.wireValue()), "restart EUR " + grain.wireValue() + " decode must be identical");
            }

            Map<String, String> afterRestart = snapshotDataRoot(configuredRoot);
            assertEquals(phaseA.afterRepeatSnapshot(), afterRestart,
                    "restarting the program must not create, delete or change any persisted byte");
            return new PhaseBOutcome(usdPublished.get(0), eurPublished.get(0), usdDaily, eurDaily,
                    usdRestart, eurRestart, afterRestart);
        }
    }

    private static void assertRealRawLayer(
            DataRoot dataRoot, Day2CycleResult cycle, RawReceiptV1 raw, String label) throws IOException {
        assertEquals(MonitorSeriesDefaults.PBOC_SOURCE_NAME, raw.actualSourceName(),
                label + " must retain the official PBOC source name");
        assertEquals(cycle.businessDate(), raw.sourceBusinessDate(), label + " must retain the business date");
        assertNotNull(raw.receivedAt(), label + " must retain the collection time");
        assertEquals(cycle.payloadSha256(), raw.payloadSha256(),
                label + " must retain the official payload hash");
        assertEquals(1, raw.configVersion());
        assertEquals(cycle.acquisitionId(), raw.acquisitionId(), label + " must share the acquisition id");
        assertEquals(200, raw.httpStatus());
        assertTrue(raw.rawRef().startsWith("raw/formal/official_web/" + raw.itemId() + "/"
                + cycle.businessDate().substring(0, 7).replace('-', '/') + "/"),
                label + " rawRef must use the frozen raw routing");
        Path rawPath = dataRoot.resolveDataRef(raw.rawRef());
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(raw.rawRef()));
        assertTrue(Files.isRegularFile(rawPath));
        assertTrue(Files.isRegularFile(manifestPath));
        ManifestV1 manifest = JsonV1Codec.decodeFile(Files.readAllBytes(manifestPath), ManifestV1.class);
        assertEquals(FileDigest.sha256(rawPath), manifest.fileSha256(), label + " raw manifest sha must match");
        assertEquals(Files.size(rawPath), manifest.byteLength());
        assertTrue(ManifestVerifier.matches(dataRoot, raw.rawRef(), rawPath, manifestPath, List.of(raw.runId())),
                label + " raw file/manifest must verify");
    }

    private static void assertPublishedGate(LifecycleTimelineV1 timeline, String runId) {
        assertEquals(ProcessingStage.PUBLISHED, timeline.current().processingStage(),
                runId + " must be PUBLISHED");
        assertTrue(timeline.current().validationStatus() == ValidationStatus.VERIFIED
                        || timeline.current().validationStatus() == ValidationStatus.VERIFIED_WITH_NOTICE,
                runId + " must be verified-class at the publish gate");
        assertNotNull(timeline.current().publishRef(), runId + " must carry a publishRef");
        assertNotNull(timeline.current().publishedAt(), runId + " must carry publishedAt");
        assertEquals(timeline.current().publishedAt(), timeline.current().updatedAt());
    }

    private static void assertDailyRow(
            Day2CycleResult cycle,
            DailyRecordV1 row,
            RawReceiptV1 raw,
            OffsetDateTime publishedAt,
            String label
    ) {
        assertEquals(cycle.businessDate(), row.businessDate());
        assertEquals(raw.rawValue(), row.sum(), label + " daily sum must equal the raw official value");
        assertEquals(expectedAvg(raw.rawValue()), row.avg(), label + " daily avg must be the scale-8 recompute");
        assertEquals(1, row.validCount());
        assertEquals(ProcessingStage.PUBLISHED, row.processingStage());
        assertEquals(ValidationStatus.VERIFIED, row.validationStatus());
        assertEquals(List.of(1), row.configVersions());
        assertEquals(publishedAt, row.updatedAt(),
                label + " daily.updatedAt must be the PUBLISHED input publishedAt (DEC-052)");
        assertEquals(1, row.inputRefs().size());
        assertTrue(row.inputRefs().get(0).runId().startsWith(
                        "pboc-" + label.toLowerCase() + "-" + cycle.businessDate().replace("-", "") + "-"),
                label + " daily inputRef must point at the real run");
        assertEquals(MonitorSeriesDefaults.USD_CNY_ITEM_ID.equals(raw.itemId()) ? "CNY/1 USD" : "CNY/1 EUR", row.unit());
    }

    private static void assertAggregateGrain(
            DataRoot dataRoot,
            Day2CycleResult cycle,
            String itemId,
            AggregateGrain grain,
            String expectedValue,
            DailyRecordV1 dailyRow,
            Map<String, AggregateRecordV1> byGrain
    ) throws IOException {
        String ref = DataPaths.aggregateRef(itemId, grain.wireValue(),
                LocalDate.parse(cycle.businessDate()).getYear());
        AggregateRecordV1 row = byGrain.get(grain.wireValue());
        assertNotNull(row, itemId + " " + grain.wireValue() + " must be persisted");
        assertEquals(expectedValue, row.sum(), itemId + " " + grain.wireValue() + " sum must equal the recomputed daily avg");
        assertEquals(expectedValue, row.avg());
        assertEquals(expectedValue, row.min());
        assertEquals(expectedValue, row.max());
        assertEquals(1, row.validCount());
        assertEquals(List.of(1), row.configVersions());
        assertEquals(dailyRow.updatedAt(), row.calculatedAt(),
                itemId + " " + grain.wireValue() + " calculatedAt must be max(daily.updatedAt) (DEC-055)");
        assertEquals(1, row.inputRefs().size());

        Path aggregatePath = dataRoot.resolveDataRef(ref);
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(ref));
        assertTrue(Files.isRegularFile(aggregatePath));
        assertTrue(Files.isRegularFile(manifestPath));
        ManifestV1 manifest = JsonV1Codec.decodeFile(Files.readAllBytes(manifestPath), ManifestV1.class);
        assertEquals(ManifestV1.COMMITTED, manifest.commitState());
        assertEquals(FileDigest.sha256(aggregatePath), manifest.fileSha256());
        assertEquals(Files.size(aggregatePath), manifest.byteLength());
        assertEquals(1, manifest.rowCount());
        assertTrue(manifest.sourceRunIds().contains(cycle.usdRunId())
                        || manifest.sourceRunIds().contains(cycle.eurRunId()),
                itemId + " aggregate manifest must reference its real source run");
        assertTrue(ManifestVerifier.matches(dataRoot, ref, aggregatePath, manifestPath),
                itemId + " aggregate file/manifest must verify");
    }

    private static Map<String, AggregateRecordV1> readAggregateGrains(DataRoot dataRoot, String itemId)
            throws IOException {
        Map<String, AggregateRecordV1> byGrain = new LinkedHashMap<>();
        for (AggregateGrain grain : List.of(AggregateGrain.MONTH, AggregateGrain.QUARTER,
                AggregateGrain.HALFYEAR, AggregateGrain.YEAR)) {
            String ref = DataPaths.aggregateRef(itemId, grain.wireValue(), 2026);
            List<AggregateRecordV1> rows = CsvV1Codec.decodeAggregate(
                    Files.readAllBytes(dataRoot.resolveDataRef(ref)));
            assertEquals(1, rows.size(), itemId + " " + grain.wireValue() + " must hold exactly one row");
            byGrain.put(grain.wireValue(), rows.get(0));
        }
        return byGrain;
    }

    private static Map<String, AggregateRecordV1> readAggregateGrainsViaReader(
            AggregateReadService reader, String itemId
    ) {
        Map<String, AggregateRecordV1> byGrain = new LinkedHashMap<>();
        for (AggregateGrain grain : List.of(AggregateGrain.MONTH, AggregateGrain.QUARTER,
                AggregateGrain.HALFYEAR, AggregateGrain.YEAR)) {
            AggregateReadService.AggregateFile file = reader.read(itemId, grain, 2026);
            assertNotNull(file, "restart must discover the persisted " + itemId + " " + grain.wireValue());
            assertEquals(ManifestV1.COMMITTED, file.manifest().commitState());
            assertEquals(FileDigest.sha256(file.csvBytes()), file.manifest().fileSha256());
            assertEquals(1, file.rows().size());
            byGrain.put(grain.wireValue(), file.rows().get(0));
        }
        return byGrain;
    }

    private static DailyRecordV1 readSingleDailyRow(DataRoot dataRoot, String dailyRef) throws IOException {
        assertNotNull(dailyRef, "the daily file must exist for the persisted chain");
        Path dailyPath = dataRoot.resolveDataRef(dailyRef);
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(dailyRef));
        assertTrue(Files.isRegularFile(dailyPath));
        assertTrue(Files.isRegularFile(manifestPath));
        ManifestV1 manifest = JsonV1Codec.decodeFile(Files.readAllBytes(manifestPath), ManifestV1.class);
        assertEquals(FileDigest.sha256(dailyPath), manifest.fileSha256());
        assertEquals(Files.size(dailyPath), manifest.byteLength());
        assertEquals(1, manifest.rowCount());
        List<DailyRecordV1> rows = CsvV1Codec.decodeDaily(Files.readAllBytes(dailyPath));
        assertEquals(1, rows.size());
        return rows.get(0);
    }

    private static void assertRepeatOnlyAddsFrozenConflictEvidence(
            Map<String, String> before, Map<String, String> after
    ) {
        List<String> changed = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        after.forEach((ref, hash) -> {
            if (before.containsKey(ref) && !before.get(ref).equals(hash)) {
                changed.add(ref);
            }
        });
        before.forEach((ref, hash) -> {
            if (!after.containsKey(ref)) {
                deleted.add(ref);
            }
        });
        assertTrue(changed.isEmpty(), "a repeat trigger must never alter existing bytes: " + changed);
        assertTrue(deleted.isEmpty(), "a repeat trigger must never delete files: " + deleted);
        List<String> addedBusiness = new ArrayList<>();
        after.forEach((ref, hash) -> {
            if (!before.containsKey(ref) && !ref.endsWith(".manifest.json")) {
                addedBusiness.add(ref);
            }
        });
        for (String ref : addedBusiness) {
            assertTrue(ref.startsWith("runtime/conflicts/raw/"),
                    "a repeat trigger must only add frozen conflict evidence: " + ref);
        }
        assertFalse(addedBusiness.isEmpty(), "the different-hash repeat must leave frozen conflict evidence");
    }

    private static String expectedAvg(String rawValue) {
        return new BigDecimal(rawValue).setScale(8, RoundingMode.HALF_UP).toPlainString();
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
    }

    private static RawReceiptV1 decodeRaw(DataRoot dataRoot, String ref) throws IOException {
        return JsonV1Codec.decodeFile(Files.readAllBytes(dataRoot.resolveDataRef(ref)), RawReceiptV1.class);
    }

    private static LifecycleTimelineV1 decodeTimeline(DataRoot dataRoot, String runId) throws IOException {
        return JsonV1Codec.decodeFile(Files.readAllBytes(dataRoot.resolveDataRef(DataPaths.stagingRef(runId))),
                LifecycleTimelineV1.class);
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

    private void writeEvidence(Path configuredRoot, PhaseAOutcome phaseA, PhaseBOutcome phaseB) throws IOException {
        Day2CycleResult cycle = phaseA.cycle();
        System.out.printf("AT_SRC_002 cycle businessDate=%s acquisitionId=%s payloadSha256=%s%n",
                cycle.businessDate(), cycle.acquisitionId(), cycle.payloadSha256());
        System.out.printf("AT_SRC_002 usd rawValue=%s dailyAvg=%s dailyRef=%s runId=%s%n",
                phaseA.usdRaw().rawValue(), phaseA.usdDailyRow().avg(), cycle.usdDailyRef(), cycle.usdRunId());
        System.out.printf("AT_SRC_002 eur rawValue=%s dailyAvg=%s dailyRef=%s runId=%s%n",
                phaseA.eurRaw().rawValue(), phaseA.eurDailyRow().avg(), cycle.eurDailyRef(), cycle.eurRunId());
        System.out.printf("AT_SRC_002 publishRef=%s aggregateRefs=%d+%d restartOfflineRead=PASS conflictEvidence=true%n",
                phaseA.usdTimeline().current().publishRef(),
                cycle.usdAggregateRefs().size(), cycle.eurAggregateRefs().size());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("acceptanceTest", "AT-SRC-002 Day1-Day2 PBOC dual-currency real acquisition and file closed loop");
        summary.put("executedAt", OffsetDateTime.now(SHANGHAI).withNano(0).toString());
        summary.put("javaRuntime", System.getProperty("java.version"));
        summary.put("springBootVersion", SpringBootVersion.getVersion());
        summary.put("dataRoot", configuredRoot.toString());
        summary.put("realSource", MonitorSeriesDefaults.PBOC_SOURCE_NAME);
        summary.put("businessDate", cycle.businessDate());
        summary.put("acquisitionId", cycle.acquisitionId());
        summary.put("payloadSha256", cycle.payloadSha256());
        summary.put("usd", Map.ofEntries(
                Map.entry("itemId", phaseA.usdRaw().itemId()),
                Map.entry("runId", cycle.usdRunId()),
                Map.entry("rawRef", cycle.usdRawRef()),
                Map.entry("rawValue", phaseA.usdRaw().rawValue()),
                Map.entry("dailyRef", cycle.usdDailyRef()),
                Map.entry("dailySum", phaseA.usdDailyRow().sum()),
                Map.entry("dailyAvg", phaseA.usdDailyRow().avg()),
                Map.entry("aggregateRefs", cycle.usdAggregateRefs()),
                Map.entry("rawFileSha256", FileDigest.sha256(configuredRoot.resolve(cycle.usdRawRef().replace('/', '\\')))),
                Map.entry("dailyUpdatedAt", phaseA.usdDailyRow().updatedAt().toString()),
                Map.entry("aggregateCalculatedAt", phaseA.usdAggregates().get("month").calculatedAt().toString())));
        summary.put("eur", Map.ofEntries(
                Map.entry("itemId", phaseA.eurRaw().itemId()),
                Map.entry("runId", cycle.eurRunId()),
                Map.entry("rawRef", cycle.eurRawRef()),
                Map.entry("rawValue", phaseA.eurRaw().rawValue()),
                Map.entry("dailyRef", cycle.eurDailyRef()),
                Map.entry("dailySum", phaseA.eurDailyRow().sum()),
                Map.entry("dailyAvg", phaseA.eurDailyRow().avg()),
                Map.entry("aggregateRefs", cycle.eurAggregateRefs()),
                Map.entry("rawFileSha256", FileDigest.sha256(configuredRoot.resolve(cycle.eurRawRef().replace('/', '\\')))),
                Map.entry("dailyUpdatedAt", phaseA.eurDailyRow().updatedAt().toString()),
                Map.entry("aggregateCalculatedAt", phaseA.eurAggregates().get("month").calculatedAt().toString())));
        summary.put("publishGate", Map.of(
                "usdPublishRef", phaseA.usdTimeline().current().publishRef(),
                "eurPublishRef", phaseA.eurTimeline().current().publishRef(),
                "usdPublishedAt", phaseA.usdTimeline().current().publishedAt().toString(),
                "eurPublishedAt", phaseA.eurTimeline().current().publishedAt().toString()));
        summary.put("repeatTrigger", Map.of(
                "outcome", "FROZEN_CONFLICT_EVIDENCE",
                "failureKind", PbocCollectionFailureKind.PERSISTENCE_FAILED.name(),
                "noDoublePublish", true,
                "dailyBytesUnchanged", true,
                "aggregateBytesUnchanged", true));
        summary.put("restart", Map.of(
                "outcome", "PASS",
                "secondSpringContext", true,
                "offlineReadOnly", true,
                "aggregateReader", "AggregateReadService",
                "noProcessYearOrRebuild", true,
                "filesUnchanged", true,
                "publishedRecordsReadable", true,
                "dailyDecodedIdentical", phaseA.usdDailyRow().equals(phaseB.usdDailyRow()),
                "aggregateDecodedIdentical", phaseA.usdAggregates().get("month").equals(phaseB.usdAggregates().get("month"))));
        summary.put("independentRecompute", Map.of(
                "usdExpectedDailyAvg", expectedAvg(phaseA.usdRaw().rawValue()),
                "usdActualDailyAvg", phaseA.usdDailyRow().avg(),
                "eurExpectedDailyAvg", expectedAvg(phaseA.eurRaw().rawValue()),
                "eurActualDailyAvg", phaseA.eurDailyRow().avg(),
                "precision", "BigDecimal from String, HALF_UP at scale 8, no float/double"));
        summary.put("result", "PASS");

        String evidenceDirValue = System.getProperty("at-src-002.evidence-dir");
        if (evidenceDirValue != null && !evidenceDirValue.isBlank()) {
            Path evidenceDir = Path.of(evidenceDirValue).toAbsolutePath().normalize();
            Files.createDirectories(evidenceDir);
            ObjectMapper mapper = JsonV1Codec.mapper();
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(summary);
            Files.write(evidenceDir.resolve("at-src-002-summary.json"), bytes);
            System.out.printf("AT_SRC_002 evidence written=%s%n", evidenceDir.resolve("at-src-002-summary.json"));
        }
    }

    private record PhaseAOutcome(
            Day2CycleResult cycle,
            RawReceiptV1 usdRaw,
            RawReceiptV1 eurRaw,
            LifecycleTimelineV1 usdTimeline,
            LifecycleTimelineV1 eurTimeline,
            DailyRecordV1 usdDailyRow,
            DailyRecordV1 eurDailyRow,
            Map<String, AggregateRecordV1> usdAggregates,
            Map<String, AggregateRecordV1> eurAggregates,
            PublishedRecord usdPublished,
            PublishedRecord eurPublished,
            Map<String, String> afterRepeatSnapshot
    ) {
    }

    private record PhaseBOutcome(
            PublishedRecord usdPublished,
            PublishedRecord eurPublished,
            DailyRecordV1 usdDailyRow,
            DailyRecordV1 eurDailyRow,
            Map<String, AggregateRecordV1> usdAggregates,
            Map<String, AggregateRecordV1> eurAggregates,
            Map<String, String> afterRestartSnapshot
    ) {
    }
}
