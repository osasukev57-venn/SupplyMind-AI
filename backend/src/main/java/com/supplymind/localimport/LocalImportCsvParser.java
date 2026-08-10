package com.supplymind.localimport;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.SchemaValidationException;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * D3-T05 safe UTF-8 CSV parser for the LocalImport template. Strict UTF-8 decoding (fail
 * closed on GBK/other encodings or BOM), quote-aware field splitting (commas, quotes and
 * embedded line breaks inside quoted fields), frozen header validation, and per-row
 * structural errors. It never guesses a format.
 */
public final class LocalImportCsvParser {

    public static final List<String> TEMPLATE_HEADER = List.of(
            "schemaVersion", "itemId", "businessDate", "value", "unit", "currency",
            "actualSourceName", "sourceReference", "sourceUrl");

    public ParseResult parse(byte[] utf8Bytes) {
        if (utf8Bytes == null || utf8Bytes.length == 0) {
            return ParseResult.fileFailed("EMPTY_FILE");
        }
        if (utf8Bytes.length >= 3 && (utf8Bytes[0] & 0xff) == 0xef && (utf8Bytes[1] & 0xff) == 0xbb
                && (utf8Bytes[2] & 0xff) == 0xbf) {
            return ParseResult.fileFailed("UTF8_BOM_NOT_ALLOWED");
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        final String content;
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(utf8Bytes));
            content = decoded.toString();
        } catch (CharacterCodingException exception) {
            return ParseResult.fileFailed("NOT_VALID_UTF8");
        }
        if (content.indexOf('\r') >= 0) {
            return ParseResult.fileFailed("CR_NOT_ALLOWED_USE_LF");
        }
        List<String> records = splitRecords(content);
        if (records.isEmpty() || records.get(0).isBlank()) {
            return ParseResult.fileFailed("MISSING_HEADER");
        }
        List<String> header = splitFields(records.get(0));
        if (!header.equals(TEMPLATE_HEADER)) {
            return ParseResult.fileFailed("UNEXPECTED_HEADER");
        }
        List<LocalImportRow> rows = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        for (int index = 1; index < records.size(); index++) {
            String record = records.get(index);
            if (record.isBlank()) {
                continue;
            }
            List<String> fields = splitFields(record);
            int rowNumber = index + 1;
            if (fields.size() != TEMPLATE_HEADER.size()) {
                errors.add(new RowError(rowNumber, "COLUMN_COUNT_MISMATCH"));
                continue;
            }
            try {
                String sourceUrl = fields.get(8);
                rows.add(new LocalImportRow(
                        fields.get(0), fields.get(1), fields.get(2), fields.get(3), fields.get(4),
                        fields.get(5), fields.get(6), fields.get(7),
                        sourceUrl == null || sourceUrl.isBlank() ? null : sourceUrl));
            } catch (SchemaValidationException exception) {
                errors.add(new RowError(rowNumber, "FIELD_INVALID"));
            }
        }
        return new ParseResult(null, rows, errors);
    }

    /** Splits records on LF while respecting quoted fields that may contain embedded LF. */
    private static List<String> splitRecords(String content) {
        List<String> records = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int index = 0; index < content.length(); index++) {
            char c = content.charAt(index);
            if (inQuotes) {
                if (c == '"') {
                    if (index + 1 < content.length() && content.charAt(index + 1) == '"') {
                        current.append('"').append('"');
                        index++;
                    } else {
                        current.append('"');
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                current.append('"');
                inQuotes = true;
            } else if (c == '\n') {
                records.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        records.add(current.toString());
        return records;
    }

    /** Public single-record field splitter (used for idempotent row content comparison). */
    public static List<String> fieldsOf(String record) {
        return splitFields(record);
    }

    /** Quote-aware RFC4180-style field splitter; handles commas, quotes and escaped quotes. */
    private static List<String> splitFields(String record) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int index = 0; index < record.length(); index++) {
            char c = record.charAt(index);
            if (inQuotes) {
                if (c == '"') {
                    if (index + 1 < record.length() && record.charAt(index + 1) == '"') {
                        current.append('"');
                        index++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    public record ParseResult(String fileError, List<LocalImportRow> rows, List<RowError> rowErrors) {
        public ParseResult {
            rows = List.copyOf(rows == null ? List.of() : rows);
            rowErrors = List.copyOf(rowErrors == null ? List.of() : rowErrors);
        }

        static ParseResult fileFailed(String reason) {
            return new ParseResult(reason, List.of(), List.of());
        }

        public boolean fileFailed() {
            return fileError != null;
        }
    }

    public record RowError(int rowNumber, String reason) {
    }
}
