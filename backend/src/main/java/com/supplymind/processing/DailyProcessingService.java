package com.supplymind.processing;

import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.DailyInputRefV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.LifecycleSnapshotV1;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyTargetRole;
import com.supplymind.foundation.storage.DirtyTransactionType;
import com.supplymind.foundation.storage.FileTransactionTarget;
import com.supplymind.foundation.storage.ManifestFactory;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.StorageException;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.validation.VersionedConfigReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * D2-T03 daily processing: gate-eligible PUBLISHED+VERIFIED-class records of one item and
 * business month are converted to frozen daily CSV rows (arithmetic-mean-v1) and persisted
 * atomically at processed/daily/&lt;itemId&gt;/YYYY-MM.csv with an adjacent manifest.
 * Only the frozen publish gate predicate is accepted; nothing else is ever read as input.
 */
public final class DailyProcessingService {

    private final DataRoot dataRoot;
    private final TimelineStore timelineStore;
    private final AtomicFileStore fileStore;
    private final Clock clock;

    public DailyProcessingService(
            DataRoot dataRoot,
            TimelineStore timelineStore,
            AtomicFileStore fileStore,
            Clock clock
    ) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.timelineStore = Objects.requireNonNull(timelineStore, "timelineStore");
        this.fileStore = Objects.requireNonNull(fileStore, "fileStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DailyResult processMonth(String itemId, YearMonth month) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(month, "month");
        List<DailyInput> inputs = collectEligibleInputs(itemId, month);
        if (inputs.isEmpty()) {
            return new DailyResult(null, List.of());
        }
        OffsetDateTime updatedAt = now();
        List<DailyRecordV1> rows = DailyMeanCalculator.calculate(inputs, updatedAt);
        byte[] csvBytes = CsvV1Codec.encodeDaily(rows);
        String dailyRef = DataPaths.dailyRef(itemId, month);
        List<String> sourceRunIds = rows.stream()
                .flatMap(row -> row.inputRefs().stream())
                .map(DailyInputRefV1::runId)
                .distinct()
                .sorted()
                .toList();
        String minBusinessDate = rows.stream().map(DailyRecordV1::businessDate).min(String::compareTo).orElseThrow();
        String maxBusinessDate = rows.stream().map(DailyRecordV1::businessDate).max(String::compareTo).orElseThrow();
        ManifestV1 manifest = ManifestFactory.csv(
                dailyRef, csvBytes, rows.size(), minBusinessDate, maxBusinessDate, sourceRunIds, updatedAt);
        byte[] manifestBytes = JsonV1Codec.encodeFile(manifest);
        fileStore.commit(
                "daily-" + itemId + "-" + month,
                DirtyTransactionType.SINGLE_FILE,
                updatedAt,
                List.of(new FileTransactionTarget(
                        DirtyTargetRole.BUSINESS_FILE, dailyRef, csvBytes, manifestBytes, false)));
        return new DailyResult(dailyRef, rows);
    }

    private List<DailyInput> collectEligibleInputs(String itemId, YearMonth month) {
        List<DailyInput> inputs = new ArrayList<>();
        for (String runId : stagingRunIds()) {
            LifecycleTimelineV1 timeline = timelineStore.read(runId);
            LifecycleSnapshotV1 current = timeline.current();
            if (current.processingStage() != ProcessingStage.PUBLISHED
                    || (current.validationStatus() != ValidationStatus.VERIFIED
                    && current.validationStatus() != ValidationStatus.VERIFIED_WITH_NOTICE)) {
                continue;
            }
            var candidate = current.candidate();
            if (candidate == null || !candidate.itemId().equals(itemId)
                    || !YearMonth.from(LocalDate.parse(candidate.businessDate())).equals(month)) {
                continue;
            }
            RawReceiptV1 raw = readRaw(timeline.rawRef(), runId);
            MonitorSeriesConfigV1 config = VersionedConfigReader.readVersion(dataRoot, raw.configVersion());
            MonitorSeriesItemV1 item = config.requireItem(raw.itemId());
            inputs.add(new DailyInput(
                    candidate.itemId(),
                    candidate.businessDate(),
                    candidate.value(),
                    candidate.currency(),
                    candidate.unit(),
                    candidate.providerType(),
                    candidate.actualSourceName(),
                    candidate.accessMethod(),
                    current.validationStatus(),
                    current.validationVersion(),
                    raw.configVersion(),
                    timeline.runId(),
                    timeline.rawRef(),
                    timeline.currentRecordVersion(),
                    item.calculationVersion(),
                    item.calculationScale(),
                    item.displayScale(),
                    item.roundingMode(),
                    item.calendarVersion()));
        }
        inputs.sort(Comparator.comparing(DailyInput::runId));
        return List.copyOf(inputs);
    }

    private List<String> stagingRunIds() {
        Path stagingDir = dataRoot.resolveInternalRelative("staging");
        if (!Files.isDirectory(stagingDir)) {
            return List.of();
        }
        List<String> runIds = new ArrayList<>();
        try (Stream<Path> files = Files.list(stagingDir)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().endsWith(".manifest.json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> runIds.add(path.getFileName().toString()
                            .substring(0, path.getFileName().toString().length() - ".json".length())));
        } catch (IOException exception) {
            throw new StorageException("Unable to list lifecycle timelines for daily processing", exception);
        }
        return List.copyOf(runIds);
    }

    private RawReceiptV1 readRaw(String rawRef, String runId) {
        Path rawPath = dataRoot.resolveDataRef(rawRef);
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(rawRef));
        if (!Files.isRegularFile(rawPath)
                || !ManifestVerifier.matches(dataRoot, rawRef, rawPath, manifestPath, List.of(runId))) {
            throw new StorageException("Daily processing requires a manifest-valid raw: " + rawRef);
        }
        try {
            return JsonV1Codec.decodeFile(Files.readAllBytes(rawPath), RawReceiptV1.class);
        } catch (IOException exception) {
            throw new StorageException("Unable to read raw " + rawRef, exception);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
