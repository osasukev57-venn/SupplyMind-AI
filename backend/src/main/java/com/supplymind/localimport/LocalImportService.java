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
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
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
 * D3-T05 controlled LocalImport entry, raw-first for both frozen formats (CSV and XLSX):
 * the complete original file bytes are persisted as an immutable source-level import receipt
 * with a COMMITTED manifest BEFORE any decode/parse. Format is detected from the bytes (ZIP
 * magic = XLSX), never from the file name. Each accepted row becomes an item-level raw whose
 * payload is the exact original byte span of the logical record (CSV) or the deterministic
 * cell facts of the record (XLSX). LocalImport is never auto-verified or auto-published.
 */
public final class LocalImportService {

    private final DataRoot dataRoot;
    private final RawReceiptStore rawReceiptStore;
    private final LocalImportFileStore importFileStore;
    private final TimelineStore timelineStore;
    private final LocalImportCsvParser parser;
    private final Clock clock;

    public LocalImportService(
            DataRoot dataRoot,
            RawReceiptStore rawReceiptStore,
            LocalImportFileStore importFileStore,
            TimelineStore timelineStore,
            LocalImportCsvParser parser,
            Clock clock
    ) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.rawReceiptStore = Objects.requireNonNull(rawReceiptStore, "rawReceiptStore");
        this.importFileStore = Objects.requireNonNull(importFileStore, "importFileStore");
        this.timelineStore = Objects.requireNonNull(timelineStore, "timelineStore");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public LocalImportResult importFile(byte[] originalBytes) {
        Objects.requireNonNull(originalBytes, "originalBytes");
        String importId = "import-file-" + FileDigest.sha256(originalBytes);
        OffsetDateTime receivedAt = OffsetDateTime.now(clock);
        importFileStore.store(new LocalImportReceiptV1(
                SchemaV1.VERSION, DataPaths.importRef(importId), importId, receivedAt,
                originalBytes.length, "base64",
                Base64.getEncoder().encodeToString(originalBytes), FileDigest.sha256(originalBytes)));

        if (isZipXlsx(originalBytes)) {
            return importXlsx(originalBytes, importId, receivedAt);
        }
        return importCsv(originalBytes, importId, receivedAt);
    }

    private LocalImportResult importCsv(byte[] originalBytes, String importId, OffsetDateTime receivedAt) {
        LocalImportCsvParser.ParseResult parsed = parser.parse(originalBytes);
        if (parsed.fileFailed()) {
            return new LocalImportResult(parsed.fileError(), List.of(), parsed.rowErrors());
        }
        MonitorSeriesConfigV1 config = loadActiveConfig();
        List<LocalImportResult.RowOutcome> accepted = new ArrayList<>();
        List<LocalImportCsvParser.RowError> rowErrors = new ArrayList<>(parsed.rowErrors());

        for (int index = 0; index < parsed.rows().size(); index++) {
            LocalImportRow row = parsed.rows().get(index);
            int rowNumber = index + 2;
            String rowError = validateRowMechanically(row, config);
            if (rowError != null) {
                rowErrors.add(new LocalImportCsvParser.RowError(rowNumber, rowError));
                continue;
            }
            Optional<LocalImportResult.RowOutcome> replay = findIdempotentReplay(row, false);
            if (replay.isPresent()) {
                accepted.add(replay.get());
                continue;
            }
            byte[] rowPayload = parsed.rowSpans().get(index).bytes();
            accepted.add(persistRow(row, config, importId, receivedAt, rowNumber, rowPayload));
        }
        return new LocalImportResult(null, accepted, rowErrors);
    }

    private LocalImportResult importXlsx(byte[] originalBytes, String importId, OffsetDateTime receivedAt) {
        MonitorSeriesConfigV1 config = loadActiveConfig();
        final List<LocalImportRow> rows = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(originalBytes))) {
            Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
            if (sheet == null) {
                return new LocalImportResult("MISSING_SHEET", List.of(), List.of());
            }
            DataFormatter formatter = new DataFormatter();
            Row headerRow = sheet.getRow(0);
            List<String> header = readRowAsStrings(sheet, headerRow, formatter);
            if (header == null || !header.equals(LocalImportCsvParser.TEMPLATE_HEADER)) {
                return new LocalImportResult("UNEXPECTED_HEADER", List.of(), List.of());
            }
            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                List<String> fields = readRowAsStrings(sheet, row, formatter);
                if (fields == null) {
                    return new LocalImportResult("NON_TEXT_CELL", List.of(), List.of());
                }
                if (fields.stream().allMatch(String::isBlank)) {
                    continue;
                }
                try {
                    String sourceUrl = fields.get(8);
                    rows.add(new LocalImportRow(
                            fields.get(0), fields.get(1), fields.get(2), fields.get(3), fields.get(4),
                            fields.get(5), fields.get(6), fields.get(7),
                            sourceUrl == null || sourceUrl.isBlank() ? null : sourceUrl));
                } catch (com.supplymind.foundation.model.SchemaValidationException exception) {
                    return new LocalImportResult("FIELD_INVALID", List.of(), List.of());
                }
            }
        } catch (IOException | RuntimeException exception) {
            return new LocalImportResult("INVALID_XLSX", List.of(), List.of());
        }

        List<LocalImportResult.RowOutcome> accepted = new ArrayList<>();
        List<LocalImportCsvParser.RowError> rowErrors = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            LocalImportRow row = rows.get(index);
            int rowNumber = index + 2;
            String rowError = validateRowMechanically(row, config);
            if (rowError != null) {
                rowErrors.add(new LocalImportCsvParser.RowError(rowNumber, rowError));
                continue;
            }
            Optional<LocalImportResult.RowOutcome> replay = findIdempotentReplay(row, true);
            if (replay.isPresent()) {
                accepted.add(replay.get());
                continue;
            }
            // DEC-057/L354: the item raw payload is the ORIGINAL FULL XLSX file bytes; the
            // parsed cell facts are derived evidence and never a raw payload.
            accepted.add(persistRow(row, config, importId, receivedAt, rowNumber, originalBytes));
        }
        return new LocalImportResult(null, accepted, rowErrors);
    }

    /**
     * XLSX cells are read as text cells only: a numeric/formula cell (which would surface as a
     * binary double) is rejected instead of being converted, so the formal value never passes
     * through float/double before BigDecimal.
     */
    private static List<String> readRowAsStrings(Sheet sheet, Row row, DataFormatter formatter) {
        if (row == null) {
            return null;
        }
        List<String> fields = new ArrayList<>();
        for (int column = 0; column < LocalImportCsvParser.TEMPLATE_HEADER.size(); column++) {
            Cell cell = row.getCell(column);
            if (cell == null) {
                fields.add("");
                continue;
            }
            if (cell.getCellType() != CellType.STRING) {
                return null;
            }
            fields.add(cell.getStringCellValue());
        }
        return fields;
    }

    private LocalImportResult.RowOutcome persistRow(
            LocalImportRow row,
            MonitorSeriesConfigV1 config,
            String importId,
            OffsetDateTime receivedAt,
            int rowNumber,
            byte[] rowPayload
    ) {
        String contentHash = FileDigest.sha256(JsonV1Codec.encodeFile(row));
        String runId = "import-" + row.itemId() + "-" + row.businessDate().replace("-", "") + "-" + contentHash;
        String rawRef = RawReceiptV1.deriveRawRef(Mode.FORMAL, ProviderType.LOCAL_IMPORT,
                row.itemId(), receivedAt, runId);
        MonitorSeriesItemV1 item = config.requireItem(row.itemId());
        RawReceiptV1 raw = new RawReceiptV1(
                SchemaV1.VERSION, rawRef, importId, runId, Mode.FORMAL,
                ProviderType.LOCAL_IMPORT, AccessMethod.LOCAL_IMPORT, config.configVersion(),
                item.actualSourceName(), row.sourceUrl(), row.sourceReference(), row.itemId(),
                row.businessDate(), row.businessDate(), null, null,
                receivedAt, receivedAt, row.value(), row.unit(), row.currency(),
                null, null, "text/csv", "base64",
                Base64.getEncoder().encodeToString(rowPayload),
                FileDigest.sha256(rowPayload), null, receivedAt, null);
        rawReceiptStore.store(raw);
        timelineStore.createInitial(runId, rawRef, receivedAt);
        return new LocalImportResult.RowOutcome(
                rowNumber, runId, rawRef, DataPaths.stagingRef(runId),
                ProcessingStage.RECEIVED, ValidationStatus.PENDING,
                LocalImportResult.ImportMode.NEW);
    }

    private static boolean isZipXlsx(byte[] bytes) {
        return bytes.length >= 4 && (bytes[0] & 0xff) == 0x50 && (bytes[1] & 0xff) == 0x4b
                && (bytes[2] & 0xff) == 0x03 && (bytes[3] & 0xff) == 0x04;
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

    private Optional<LocalImportResult.RowOutcome> findIdempotentReplay(LocalImportRow row, boolean xlsxSource) {
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
                    if (sameRowContent(row, existing, xlsxSource)) {
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

    /**
     * Row content equality over the immutable row payload. For CSV the payload is the exact
     * original record span and is parsed back; for XLSX the payload is the original full file
     * bytes, so equality uses the structured business fields persisted on the item raw.
     * Server-generated time and the import identity never participate.
     */
    private static boolean sameRowContent(LocalImportRow row, RawReceiptV1 existing, boolean xlsxSource) {
        if (xlsxSource) {
            return row.itemId().equals(existing.itemId())
                    && row.businessDate().equals(existing.sourceBusinessDate())
                    && row.value().equals(existing.rawValue())
                    && row.unit().equals(existing.rawUnit())
                    && row.currency().equals(existing.rawCurrency())
                    && row.sourceReference().equals(existing.sourceReference())
                    && java.util.Objects.equals(row.sourceUrl(), existing.sourceUrl());
        }
        try {
            byte[] payload = Base64.getDecoder().decode(existing.payloadBase64());
            String text = new String(payload, StandardCharsets.UTF_8);
            List<String> fields = LocalImportCsvParser.fieldsOf(stripTerminator(text));
            if (fields.size() == LocalImportCsvParser.TEMPLATE_HEADER.size()) {
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
            }
            LocalImportRow parsed = JsonV1Codec.decodeFile(payload, LocalImportRow.class);
            return parsed.equals(row);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String stripTerminator(String record) {
        if (record.endsWith("\r\n")) {
            return record.substring(0, record.length() - 2);
        }
        if (record.endsWith("\n") || record.endsWith("\r")) {
            return record.substring(0, record.length() - 1);
        }
        return record;
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
