package com.supplymind.localimport;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.model.SchemaV1;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.FileDigest;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.StorageException;
import com.supplymind.foundation.storage.TimelineStore;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * D3-T05 controlled LocalImport entry: a UTF-8 CSV import file is parsed with the frozen
 * template; each accepted row becomes an immutable item-level raw (payload = the original row
 * bytes, the frozen "原始导入文件（子集）字节") with a RECEIVED+PENDING timeline. LocalImport
 * is never auto-verified or auto-published (docs/01: LocalImport must pass standardization,
 * validation and publish gates like any source). Same business key (local_import + itemId +
 * businessDate) with the same row content is IDEMPOTENT; different content creates a new
 * pending version while older raws/timelines stay immutable.
 */
public final class LocalImportService {

    private final DataRoot dataRoot;
    private final RawReceiptStore rawReceiptStore;
    private final TimelineStore timelineStore;
    private final LocalImportCsvParser parser;
    private final Clock clock;

    public LocalImportService(
            DataRoot dataRoot,
            RawReceiptStore rawReceiptStore,
            TimelineStore timelineStore,
            LocalImportCsvParser parser,
            Clock clock
    ) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.rawReceiptStore = Objects.requireNonNull(rawReceiptStore, "rawReceiptStore");
        this.timelineStore = Objects.requireNonNull(timelineStore, "timelineStore");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public LocalImportResult importFile(byte[] utf8Bytes) {
        Objects.requireNonNull(utf8Bytes, "utf8Bytes");
        LocalImportCsvParser.ParseResult parsed = parser.parse(utf8Bytes);
        if (parsed.fileFailed()) {
            return new LocalImportResult(parsed.fileError(), List.of(), parsed.rowErrors());
        }
        MonitorSeriesConfigV1 config = loadActiveConfig();
        List<LocalImportResult.RowOutcome> accepted = new ArrayList<>();
        List<LocalImportCsvParser.RowError> rowErrors = new ArrayList<>(parsed.rowErrors());
        OffsetDateTime receivedAt = OffsetDateTime.now(clock);

        for (int index = 0; index < parsed.rows().size(); index++) {
            LocalImportRow row = parsed.rows().get(index);
            int rowNumber = index + 2;
            String rowError = validateRowMechanically(row, config);
            if (rowError != null) {
                rowErrors.add(new LocalImportCsvParser.RowError(rowNumber, rowError));
                continue;
            }
            Optional<LocalImportResult.RowOutcome> replay = findIdempotentReplay(row);
            if (replay.isPresent()) {
                accepted.add(replay.get());
                continue;
            }
            String contentHash = FileDigest.sha256(JsonV1Codec.encodeFile(row));
            String runId = "import-" + row.itemId() + "-" + row.businessDate().replace("-", "") + "-" + contentHash;
            String rawRef = RawReceiptV1.deriveRawRef(Mode.FORMAL, ProviderType.LOCAL_IMPORT,
                    row.itemId(), receivedAt, runId);
            byte[] rowPayload = originalRowBytes(utf8Bytes, rowNumber);
            MonitorSeriesItemV1 item = config.requireItem(row.itemId());
            RawReceiptV1 raw = new RawReceiptV1(
                    SchemaV1.VERSION, rawRef, "import-acq-" + contentHash, runId, Mode.FORMAL,
                    ProviderType.LOCAL_IMPORT, AccessMethod.LOCAL_IMPORT, config.configVersion(),
                    item.actualSourceName(), row.sourceUrl(), row.sourceReference(), row.itemId(),
                    row.businessDate(), row.businessDate(), null, null,
                    receivedAt, receivedAt, row.value(), row.unit(), row.currency(),
                    null, null, "text/csv", "base64",
                    Base64.getEncoder().encodeToString(rowPayload),
                    FileDigest.sha256(rowPayload), null, receivedAt, null);
            rawReceiptStore.store(raw);
            timelineStore.createInitial(runId, rawRef, receivedAt);
            accepted.add(new LocalImportResult.RowOutcome(
                    rowNumber, runId, rawRef, DataPaths.stagingRef(runId),
                    ProcessingStage.RECEIVED, ValidationStatus.PENDING,
                    LocalImportResult.ImportMode.NEW));
        }
        return new LocalImportResult(null, accepted, rowErrors);
    }

    private static String validateRowMechanically(LocalImportRow row, MonitorSeriesConfigV1 config) {
        MonitorSeriesItemV1 item;
        try {
            item = config.requireItem(row.itemId());
        } catch (RuntimeException exception) {
            return "ITEM_NOT_CONFIGURED";
        }
        if (!item.enabled()
                || (item.providerType() != ProviderType.LOCAL_IMPORT
                && item.routeDecision() != RouteDecision.DIRECT_LOCAL_IMPORT)) {
            return "ITEM_NOT_LOCAL_IMPORT_ROUTE";
        }
        try {
            java.time.LocalDate.parse(row.businessDate());
        } catch (java.time.format.DateTimeParseException exception) {
            return "BUSINESS_DATE_INVALID";
        }
        if (row.value().matches("(?i).*[eE].*")) {
            return "VALUE_SCIENTIFIC_NOTATION";
        }
        try {
            new BigDecimal(row.value());
        } catch (NumberFormatException exception) {
            return "VALUE_NOT_DECIMAL";
        }
        return null;
    }

    private Optional<LocalImportResult.RowOutcome> findIdempotentReplay(LocalImportRow row) {
        Path itemDir = dataRoot.resolveInternalRelative("raw/formal/local_import/" + row.itemId());
        if (!Files.isDirectory(itemDir)) {
            return Optional.empty();
        }
        try (Stream<Path> walk = Files.walk(itemDir)) {
            List<Path> candidates = walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().endsWith(".manifest.json"))
                    .toList();
            for (Path candidate : candidates) {
                try {
                    RawReceiptV1 existing = JsonV1Codec.decodeFile(
                            Files.readAllBytes(candidate), RawReceiptV1.class);
                    if (!row.businessDate().equals(existing.sourceBusinessDate())) {
                        continue;
                    }
                    if (sameRowContent(row, existing)) {
                        String runId = existing.runId();
                        Path timelinePath = dataRoot.resolveDataRef(DataPaths.stagingRef(runId));
                        if (!Files.isRegularFile(timelinePath)) {
                            continue;
                        }
                        LifecycleTimelineV1 timeline = JsonV1Codec.decodeFile(
                                Files.readAllBytes(timelinePath), LifecycleTimelineV1.class);
                        return Optional.of(new LocalImportResult.RowOutcome(
                                0, runId, existing.rawRef(), DataPaths.stagingRef(runId),
                                timeline.current().processingStage(), timeline.current().validationStatus(),
                                LocalImportResult.ImportMode.IDEMPOTENT_REUSE));
                    }
                } catch (IOException | RuntimeException ignored) {
                    // A corrupt candidate is never a replay; it stays untouched.
                }
            }
        } catch (IOException exception) {
            throw new StorageException("Unable to scan local_import raws for " + row.itemId(), exception);
        }
        return Optional.empty();
    }

    /** DEC-057-style row content equality over the immutable row payload (server time excluded). */
    private static boolean sameRowContent(LocalImportRow row, RawReceiptV1 existing) {
        try {
            String payload = new String(Base64.getDecoder().decode(existing.payloadBase64()), StandardCharsets.UTF_8);
            List<String> fields = LocalImportCsvParser.fieldsOf(payload);
            if (fields.size() != LocalImportCsvParser.TEMPLATE_HEADER.size()) {
                return false;
            }
            return row.schemaVersion().equals(fields.get(0))
                    && row.itemId().equals(fields.get(1))
                    && row.businessDate().equals(fields.get(2))
                    && row.value().equals(fields.get(3))
                    && row.unit().equals(fields.get(4))
                    && row.currency().equals(fields.get(5))
                    && row.actualSourceName().equals(fields.get(6))
                    && row.sourceReference().equals(fields.get(7))
                    && java.util.Objects.equals(
                    row.sourceUrl(), fields.get(8).isEmpty() ? null : fields.get(8));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static LocalImportRow decodeRowFacts(RawReceiptV1 raw) {
        try {
            return JsonV1Codec.decodeFile(
                    Base64.getDecoder().decode(raw.payloadBase64()), LocalImportRow.class);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** Locates the original record line in the file so the payload stays the frozen row subset. */
    private static byte[] originalRowBytes(byte[] utf8Bytes, int targetRowNumber) {
        String content = new String(utf8Bytes, StandardCharsets.UTF_8);
        String[] records = content.split("\n", -1);
        int index = targetRowNumber - 1;
        if (index < 0 || index >= records.length) {
            throw new StorageException("Unable to locate original row " + targetRowNumber);
        }
        return records[index].getBytes(StandardCharsets.UTF_8);
    }

    private MonitorSeriesConfigV1 loadActiveConfig() {
        String activeRef = DataPaths.configActiveRef();
        Path activePath = dataRoot.resolveDataRef(activeRef);
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(activeRef));
        if (!ManifestVerifier.matches(dataRoot, activeRef, activePath, manifestPath, List.of())) {
            throw new StorageException("LocalImport requires a valid active monitor-series configuration");
        }
        try {
            return JsonV1Codec.decodeFile(Files.readAllBytes(activePath), MonitorSeriesConfigV1.class);
        } catch (IOException | RuntimeException exception) {
            throw new StorageException("Unable to read the active monitor-series configuration", exception);
        }
    }
}
