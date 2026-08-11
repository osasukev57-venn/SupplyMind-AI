package com.supplymind.validation;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.CandidateV1;
import com.supplymind.foundation.model.LifecycleSnapshotV1;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProcessingStage;
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
 * D2-T01 entry point (extended by D4-T01): RECEIVED+PENDING -> PARSED+PENDING (CandidateV1) ->
 * VALIDATED (VERIFIED / VERIFIED_WITH_NOTICE / REJECTED / CONFLICT), or RECEIVED+REJECTED when
 * standardization fails. D4-T01 dispatches by configured item kind: PBOC items keep
 * pboc-basic-validation-v1, material items (rateKind=material) use the distinct
 * material-basic-validation-v1 and the material standardizer, so Manual/LocalImport/FreePublic
 * material rows go through the same gate with no bypass. Reprocessing is idempotent: terminal
 * and VALIDATED runs are no-ops, and an identical current snapshot is never appended twice.
 */
public final class LifecycleValidationService {

    private final DataRoot dataRoot;
    private final TimelineStore timelineStore;
    private final Clock clock;
    private final PbocCandidateStandardizer standardizer = new PbocCandidateStandardizer();
    private final PbocBasicValidator validator = new PbocBasicValidator();
    private final MaterialCandidateStandardizer materialStandardizer = new MaterialCandidateStandardizer();
    private final MaterialCandidateValidator materialValidator = new MaterialCandidateValidator();

    public LifecycleValidationService(DataRoot dataRoot, TimelineStore timelineStore, Clock clock) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.timelineStore = Objects.requireNonNull(timelineStore, "timelineStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ValidationOutcome process(String runId) {
        Objects.requireNonNull(runId, "runId");
        LifecycleTimelineV1 timeline = timelineStore.read(runId);
        LifecycleSnapshotV1 current = timeline.current();
        if (isTerminalOrValidated(current)) {
            return ValidationOutcome.of(timeline);
        }

        if (current.processingStage() == ProcessingStage.RECEIVED
                && current.validationStatus() == ValidationStatus.PENDING) {
            RawReceiptV1 raw = readRaw(timeline.rawRef(), runId);
            MonitorSeriesConfigV1 config = VersionedConfigReader.readVersion(dataRoot, raw.configVersion());
            MonitorSeriesItemV1 item = config.requireItem(raw.itemId());
            StandardizationResult standardized = isMaterial(item)
                    ? materialStandardizer.standardize(raw)
                    : standardizer.standardize(raw);
            if (standardized.candidate() == null) {
                LifecycleTimelineV1 rejected = timelineStore.append(runId, new LifecycleSnapshotV1(
                        2, ProcessingStage.RECEIVED, ValidationStatus.REJECTED, null,
                        standardized.rejectionReasonCode(), null, null, null, null, now()));
                return ValidationOutcome.of(rejected);
            }
            timeline = timelineStore.append(runId, new LifecycleSnapshotV1(
                    2, ProcessingStage.PARSED, ValidationStatus.PENDING, standardized.candidate(),
                    null, null, null, null, null, now()));
        }

        LifecycleSnapshotV1 parsed = timeline.current();
        CandidateV1 candidate = Objects.requireNonNull(parsed.candidate(),
                "PARSED or later snapshots must carry CandidateV1");
        RawReceiptV1 raw = readRaw(timeline.rawRef(), runId);
        MonitorSeriesConfigV1 config = VersionedConfigReader.readVersion(dataRoot, raw.configVersion());
        MonitorSeriesItemV1 item = config.requireItem(raw.itemId());
        LocalDate today = OffsetDateTime.now(clock).atZoneSameInstant(DataPaths.SHANGHAI).toLocalDate();
        List<CandidateV1> others = scanOtherCandidates(timeline.runId(), candidate);
        ValidationVerdict verdict = isMaterial(item)
                ? materialValidator.validate(raw, candidate, item, config.mode(), today, others)
                : validator.validate(raw, candidate, item, config.mode(), today, others);
        OffsetDateTime validatedAt = now();
        LifecycleTimelineV1 validated = timelineStore.append(runId, new LifecycleSnapshotV1(
                3, ProcessingStage.VALIDATED, verdict.validationStatus(), candidate,
                verdict.reasonCode(),
                isMaterial(item)
                        ? MaterialCandidateValidator.VALIDATION_VERSION
                        : PbocBasicValidator.VALIDATION_VERSION,
                validatedAt, null, null, validatedAt));
        return ValidationOutcome.of(validated);
    }

    /** D4-T01 dispatch: material items carry rateKind "material"; PBOC items use the FX rate kind. */
    private static boolean isMaterial(MonitorSeriesItemV1 item) {
        return "material".equals(item.rateKind());
    }

    private static boolean isTerminalOrValidated(LifecycleSnapshotV1 snapshot) {
        return snapshot.processingStage() == ProcessingStage.VALIDATED
                || snapshot.processingStage() == ProcessingStage.PUBLISHED
                || (snapshot.processingStage() == ProcessingStage.RECEIVED
                && snapshot.validationStatus() == ValidationStatus.REJECTED);
    }

    private RawReceiptV1 readRaw(String rawRef, String runId) {
        Path rawPath = dataRoot.resolveDataRef(rawRef);
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(rawRef));
        if (!Files.isRegularFile(rawPath)
                || !ManifestVerifier.matches(dataRoot, rawRef, rawPath, manifestPath, List.of(runId))) {
            throw new StorageException("Validation requires a manifest-valid raw: " + rawRef);
        }
        try {
            return JsonV1Codec.decodeFile(Files.readAllBytes(rawPath), RawReceiptV1.class);
        } catch (IOException exception) {
            throw new StorageException("Unable to read raw " + rawRef, exception);
        }
    }

    private List<CandidateV1> scanOtherCandidates(String runId, CandidateV1 candidate) {
        Path stagingDir = dataRoot.resolveInternalRelative("staging");
        if (!Files.isDirectory(stagingDir)) {
            return List.of();
        }
        List<CandidateV1> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(stagingDir)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().endsWith(".manifest.json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> {
                        String otherRunId = path.getFileName().toString()
                                .substring(0, path.getFileName().toString().length() - ".json".length());
                        if (otherRunId.equals(runId)) {
                            return;
                        }
                        String stagingRef = dataRoot.toDataRef(path);
                        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(stagingRef));
                        if (!ManifestVerifier.matches(dataRoot, stagingRef, path, manifestPath, List.of(otherRunId))) {
                            throw new StorageException("Validation scan requires a manifest-valid timeline: " + stagingRef);
                        }
                        try {
                            LifecycleTimelineV1 other = JsonV1Codec.decodeFile(Files.readAllBytes(path),
                                    LifecycleTimelineV1.class);
                            ValidationStatus otherStatus = other.current().validationStatus();
                            if (otherStatus != ValidationStatus.VERIFIED
                                    && otherStatus != ValidationStatus.VERIFIED_WITH_NOTICE) {
                                return;
                            }
                            CandidateV1 otherCandidate = other.records().stream()
                                    .map(LifecycleSnapshotV1::candidate)
                                    .filter(Objects::nonNull)
                                    .findFirst()
                                    .orElse(null);
                            if (otherCandidate != null && sameBusinessKeySource(candidate, otherCandidate)) {
                                found.add(otherCandidate);
                            }
                        } catch (IOException exception) {
                            throw new StorageException("Unable to read lifecycle timeline " + stagingRef, exception);
                        }
                    });
        } catch (IOException exception) {
            throw new StorageException("Unable to scan lifecycle timelines for duplicate detection", exception);
        }
        found.sort(Comparator.comparing(CandidateV1::itemId)
                .thenComparing(CandidateV1::businessDate)
                .thenComparing(CandidateV1::value));
        return List.copyOf(found);
    }

    private static boolean sameBusinessKeySource(CandidateV1 candidate, CandidateV1 other) {
        return candidate.itemId().equals(other.itemId())
                && candidate.businessDate().equals(other.businessDate())
                && candidate.providerType() == other.providerType()
                && candidate.actualSourceName().equals(other.actualSourceName())
                && candidate.accessMethod() == other.accessMethod();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
