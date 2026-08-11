package com.supplymind.publish;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.LifecycleSnapshotV1;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.StorageException;
import com.supplymind.foundation.storage.TimelineStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * D2-T02 business read model. It exposes only PUBLISHED+VERIFIED-class records and is the
 * single business entry point: PENDING, REJECTED and CONFLICT runs are never visible here.
 * Every returned record remains traceable to its PBOC raw and lifecycle record.
 */
public final class PublishedQueryService {

    private final DataRoot dataRoot;
    private final TimelineStore timelineStore;
    private final Clock clock;

    public PublishedQueryService(DataRoot dataRoot, TimelineStore timelineStore, Clock clock) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.timelineStore = Objects.requireNonNull(timelineStore, "timelineStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<PublishedRecord> findPublished(String itemId, LocalDate businessDate) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(businessDate, "businessDate");
        LocalDate referenceDate = today();
        List<PublishedRecord> records = new ArrayList<>();
        for (String runId : stagingRunIds()) {
            LifecycleTimelineV1 timeline = timelineStore.read(runId);
            if (!isPublishedEligible(timeline.current())) {
                continue;
            }
            var candidate = timeline.current().candidate();
            if (candidate == null || !candidate.itemId().equals(itemId)
                    || !candidate.businessDate().equals(businessDate.toString())) {
                continue;
            }
            PublishedRecord record = recordOf(timeline, referenceDate);
            if (record != null) {
                records.add(record);
            }
        }
        records.sort(Comparator.comparing(PublishedRecord::publishedAt)
                .thenComparing(PublishedRecord::runId));
        return List.copyOf(records);
    }

    public PublishedRecord latestPublished(String itemId) {
        Objects.requireNonNull(itemId, "itemId");
        LocalDate referenceDate = today();
        PublishedRecord latest = null;
        for (String runId : stagingRunIds()) {
            LifecycleTimelineV1 timeline = timelineStore.read(runId);
            if (!isPublishedEligible(timeline.current())) {
                continue;
            }
            var candidate = timeline.current().candidate();
            if (candidate == null || !candidate.itemId().equals(itemId)) {
                continue;
            }
            PublishedRecord record = recordOf(timeline, referenceDate);
            if (record == null) {
                continue;
            }
            if (latest == null || isNewer(record, latest)) {
                latest = record;
            }
        }
        return latest;
    }

    private static boolean isNewer(PublishedRecord candidate, PublishedRecord current) {
        int byDate = candidate.businessDate().compareTo(current.businessDate());
        if (byDate != 0) {
            return byDate > 0;
        }
        int byPublishedAt = candidate.publishedAt().compareTo(current.publishedAt());
        if (byPublishedAt != 0) {
            return byPublishedAt > 0;
        }
        return candidate.runId().compareTo(current.runId()) > 0;
    }

    private PublishedRecord recordOf(LifecycleTimelineV1 timeline, LocalDate referenceDate) {
        RawReceiptV1 raw = readRaw(timeline.rawRef(), timeline.runId());
        if (!isFormalBusinessRaw(raw)) {
            return null;
        }
        String rawFileSha256 = readRawFileSha256(timeline.rawRef());
        return PublishedRecord.of(timeline, raw, rawFileSha256, referenceDate);
    }

    private static boolean isFormalBusinessRaw(RawReceiptV1 raw) {
        return raw.mode() == Mode.FORMAL
                && raw.providerType() != ProviderType.SYNTHETIC_DEMO
                && raw.accessMethod() != AccessMethod.SYNTHETIC_DEMO;
    }

    private static boolean isPublishedEligible(LifecycleSnapshotV1 snapshot) {
        return snapshot.processingStage() == ProcessingStage.PUBLISHED
                && (snapshot.validationStatus() == ValidationStatus.VERIFIED
                || snapshot.validationStatus() == ValidationStatus.VERIFIED_WITH_NOTICE);
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
            throw new StorageException("Unable to list lifecycle timelines for query", exception);
        }
        return List.copyOf(runIds);
    }

    private RawReceiptV1 readRaw(String rawRef, String runId) {
        Path rawPath = dataRoot.resolveDataRef(rawRef);
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(rawRef));
        if (!Files.isRegularFile(rawPath)
                || !ManifestVerifier.matches(dataRoot, rawRef, rawPath, manifestPath, List.of(runId))) {
            throw new StorageException("Query requires a manifest-valid raw: " + rawRef);
        }
        try {
            return JsonV1Codec.decodeFile(Files.readAllBytes(rawPath), RawReceiptV1.class);
        } catch (IOException exception) {
            throw new StorageException("Unable to read raw " + rawRef, exception);
        }
    }

    private String readRawFileSha256(String rawRef) {
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(rawRef));
        if (!Files.isRegularFile(manifestPath)) {
            throw new StorageException("Query requires the raw adjacent manifest: " + rawRef);
        }
        try {
            return JsonV1Codec.decodeFile(Files.readAllBytes(manifestPath), ManifestV1.class).fileSha256();
        } catch (IOException exception) {
            throw new StorageException("Unable to read raw manifest for " + rawRef, exception);
        }
    }

    private LocalDate today() {
        return OffsetDateTime.now(clock).atZoneSameInstant(DataPaths.SHANGHAI).toLocalDate();
    }
}
