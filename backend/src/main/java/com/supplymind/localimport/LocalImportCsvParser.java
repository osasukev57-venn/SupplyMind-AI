package com.supplymind.localimport;

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
 * D3-T05 safe UTF-8 CSV parser for the LocalImport template. It scans the ORIGINAL bytes so
 * every logical record keeps its exact original byte span (quoted commas, escaped quotes,
 * quoted embedded newlines, UTF-8 multi-byte characters and LF/CRLF terminators are all
 * preserved verbatim). Strict UTF-8 (fail closed on other encodings); UTF-8 BOM is rejected
 * per the frozen no-BOM convention. CR/CRLF are accepted as normal record terminators; the
 * raw bytes are never modified.
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
        if (!isStrictUtf8(utf8Bytes)) {
            return ParseResult.fileFailed("NOT_VALID_UTF8");
        }

        ScanResult scanned = scanRecords(utf8Bytes);
        if (scanned.malformedQuoting()) {
            return ParseResult.fileFailed("MALFORMED_QUOTING");
        }
        List<RecordSpan> records = scanned.records();
        if (records.isEmpty() || records.get(0).bytes().length == 0) {
            return ParseResult.fileFailed("MISSING_HEADER");
        }
        List<String> header = fieldsOf(decode(stripTerminator(records.get(0).bytes())));
        if (!header.equals(TEMPLATE_HEADER)) {
            return ParseResult.fileFailed("UNEXPECTED_HEADER");
        }

        List<RecordSpan> dataRecords = records.subList(1, records.size());
        List<RowError> errors = new ArrayList<>();
        List<RecordSpan> acceptedSpans = new ArrayList<>();
        List<LocalImportRow> acceptedRows = new ArrayList<>();
        for (int index = 0; index < dataRecords.size(); index++) {
            RecordSpan record = dataRecords.get(index);
            int rowNumber = index + 2;
            if (new String(record.bytes(), StandardCharsets.UTF_8).trim().isEmpty()) {
                continue;
            }
            List<String> fields = fieldsOf(decode(stripTerminator(record.bytes())));
            if (fields.size() != TEMPLATE_HEADER.size()) {
                errors.add(new RowError(rowNumber, "COLUMN_COUNT_MISMATCH"));
                continue;
            }
            try {
                String sourceUrl = fields.get(8);
                acceptedRows.add(new LocalImportRow(
                        fields.get(0), fields.get(1), fields.get(2), fields.get(3), fields.get(4),
                        fields.get(5), fields.get(6), fields.get(7),
                        sourceUrl == null || sourceUrl.isBlank() ? null : sourceUrl));
                acceptedSpans.add(record);
            } catch (SchemaValidationException exception) {
                errors.add(new RowError(rowNumber, "FIELD_INVALID"));
            }
        }
        return new ParseResult(null, acceptedRows, acceptedSpans, errors);
    }

    /** Byte-level record scanner: quotes are ASCII, so multi-byte UTF-8 never confuses it. */
    private static ScanResult scanRecords(byte[] bytes) {
        List<RecordSpan> records = new ArrayList<>();
        int recordStart = 0;
        boolean inQuotes = false;
        for (int index = 0; index < bytes.length; index++) {
            byte b = bytes[index];
            if (inQuotes) {
                if (b == '"') {
                    if (index + 1 < bytes.length && bytes[index + 1] == '"') {
                        index++;
                    } else {
                        inQuotes = false;
                    }
                }
            } else if (b == '"') {
                inQuotes = true;
            } else if (b == '\n') {
                records.add(new RecordSpan(recordStart, index + 1,
                        Arrays.copyOfRange(bytes, recordStart, index + 1)));
                recordStart = index + 1;
            } else if (b == '\r') {
                if (index + 1 < bytes.length && bytes[index + 1] == '\n') {
                    records.add(new RecordSpan(recordStart, index + 2,
                            Arrays.copyOfRange(bytes, recordStart, index + 2)));
                    index++;
                    recordStart = index + 1;
                } else {
                    records.add(new RecordSpan(recordStart, index + 1,
                            Arrays.copyOfRange(bytes, recordStart, index + 1)));
                    recordStart = index + 1;
                }
            }
        }
        if (inQuotes) {
            return new ScanResult(List.of(), true);
        }
        if (recordStart < bytes.length) {
            records.add(new RecordSpan(recordStart, bytes.length,
                    Arrays.copyOfRange(bytes, recordStart, bytes.length)));
        }
        return new ScanResult(records, false);
    }

    private record ScanResult(List<RecordSpan> records, boolean malformedQuoting) {
    }

    private static boolean isStrictUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException exception) {
            return false;
        }
    }

    private static String decode(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Strips the record terminator (\r\n, \n or \r) for field parsing; the span itself keeps it. */
    private static byte[] stripTerminator(byte[] recordBytes) {
        int end = recordBytes.length;
        if (end >= 2 && recordBytes[end - 2] == '\r' && recordBytes[end - 1] == '\n') {
            end -= 2;
        } else if (end >= 1 && (recordBytes[end - 1] == '\n' || recordBytes[end - 1] == '\r')) {
            end -= 1;
        }
        return Arrays.copyOfRange(recordBytes, 0, end);
    }

    /** Public single-record field splitter (used for row content comparison). */
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

    /** One logical CSV record with its exact original byte span from the source file. */
    public record RecordSpan(int byteStart, int byteEnd, byte[] bytes) {
        public RecordSpan {
            bytes = Arrays.copyOf(bytes, bytes.length);
        }
    }

    public record ParseResult(
            String fileError,
            List<LocalImportRow> rows,
            List<RecordSpan> rowSpans,
            List<RowError> rowErrors
    ) {
        public ParseResult {
            rows = List.copyOf(rows == null ? List.of() : rows);
            rowSpans = List.copyOf(rowSpans == null ? List.of() : rowSpans);
            rowErrors = List.copyOf(rowErrors == null ? List.of() : rowErrors);
        }

        static ParseResult fileFailed(String reason) {
            return new ParseResult(reason, List.of(), List.of(), List.of());
        }

        public boolean fileFailed() {
            return fileError != null;
        }
    }

    public record RowError(int rowNumber, String reason) {
    }
}
