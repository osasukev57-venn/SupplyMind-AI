package com.supplymind.foundation.codec;

import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.AggregateGrain;
import com.supplymind.foundation.model.AggregateInputRefV1;
import com.supplymind.foundation.model.AggregateRecordV1;
import com.supplymind.foundation.model.DailyInputRefV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.QualityStatus;
import com.supplymind.foundation.model.SchemaValidationException;
import com.supplymind.foundation.model.ValidationStatus;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** UTF-8/no-BOM RFC 4180 codecs for the two frozen processed-file row schemas. */
public final class CsvV1Codec {
    public static final List<String> DAILY_HEADER = List.of(
            "schemaVersion", "businessDate", "itemId", "providerType", "actualSourceName", "accessMethod",
            "processingStage", "validationStatus", "validationVersion", "configVersions", "calculationVersion",
            "calculationScale", "displayScale", "roundingMode", "calendarVersion", "sum", "validCount", "avg",
            "expectedCount", "missingCount", "complete", "currency", "unit", "inputRefs", "updatedAt"
    );
    public static final List<String> AGGREGATE_HEADER = List.of(
            "schemaVersion", "grain", "periodStart", "periodEnd", "itemId", "providerType", "actualSourceName",
            "accessMethod", "validationStatus", "validationVersion", "configVersions", "calculationVersion",
            "calculationScale", "displayScale", "roundingMode", "calendarVersion", "sum", "validCount", "avg",
            "min", "max", "expectedCount", "missingCount", "complete", "qualityStatus", "currency", "unit",
            "sourceFingerprint", "inputRefs", "calculatedAt"
    );

    private static final CSVFormat WRITE_FORMAT = CSVFormat.RFC4180.builder()
            .setRecordSeparator("\r\n")
            .build();

    private CsvV1Codec() {
    }

    public static byte[] encodeDaily(List<DailyRecordV1> rows) {
        List<DailyRecordV1> canonical = canonical(rows, DailyRecordV1.ORDER, "daily rows");
        try (StringWriter target = new StringWriter(); CSVPrinter printer = new CSVPrinter(target, WRITE_FORMAT)) {
            printer.printRecord((Iterable<?>) DAILY_HEADER);
            for (DailyRecordV1 row : canonical) {
                printer.printRecord(
                        row.schemaVersion(), row.businessDate(), row.itemId(), row.providerType().wireValue(),
                        row.actualSourceName(), row.accessMethod().wireValue(), row.processingStage().wireValue(),
                        row.validationStatus().wireValue(), row.validationVersion(),
                        JsonV1Codec.encodeCompact(row.configVersions()), row.calculationVersion(),
                        Integer.toString(row.calculationScale()), Integer.toString(row.displayScale()), row.roundingMode().name(),
                        row.calendarVersion(), row.sum(), Integer.toString(row.validCount()), row.avg(),
                        Integer.toString(row.expectedCount()), Integer.toString(row.missingCount()), Boolean.toString(row.complete()),
                        row.currency(), row.unit(), JsonV1Codec.encodeCompact(row.inputRefs()), row.updatedAt().toString()
                );
            }
            printer.flush();
            return target.toString().getBytes(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new SchemaValidationException("Unable to encode daily CSV v1: " + exception.getMessage());
        }
    }

    public static byte[] encodeAggregate(List<AggregateRecordV1> rows) {
        List<AggregateRecordV1> canonical = canonical(rows, AggregateRecordV1.ORDER, "aggregate rows");
        try (StringWriter target = new StringWriter(); CSVPrinter printer = new CSVPrinter(target, WRITE_FORMAT)) {
            printer.printRecord((Iterable<?>) AGGREGATE_HEADER);
            for (AggregateRecordV1 row : canonical) {
                printer.printRecord(
                        row.schemaVersion(), row.grain().wireValue(), row.periodStart(), row.periodEnd(), row.itemId(),
                        row.providerType().wireValue(), row.actualSourceName(), row.accessMethod().wireValue(),
                        row.validationStatus().wireValue(), row.validationVersion(), JsonV1Codec.encodeCompact(row.configVersions()),
                        row.calculationVersion(), Integer.toString(row.calculationScale()), Integer.toString(row.displayScale()),
                        row.roundingMode().name(), row.calendarVersion(), row.sum(), Integer.toString(row.validCount()), row.avg(),
                        row.min(), row.max(), Integer.toString(row.expectedCount()), Integer.toString(row.missingCount()),
                        Boolean.toString(row.complete()), row.qualityStatus().wireValue(), row.currency(), row.unit(),
                        row.sourceFingerprint(), JsonV1Codec.encodeCompact(row.inputRefs()), row.calculatedAt().toString()
                );
            }
            printer.flush();
            return target.toString().getBytes(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new SchemaValidationException("Unable to encode aggregate CSV v1: " + exception.getMessage());
        }
    }

    public static List<DailyRecordV1> decodeDaily(byte[] utf8Bytes) {
        List<CSVRecord> records = parse(utf8Bytes, DAILY_HEADER, "daily");
        List<DailyRecordV1> rows = new ArrayList<>();
        for (int index = 1; index < records.size(); index++) {
            CSVRecord row = records.get(index);
            requireColumnCount(row, DAILY_HEADER, "daily");
            rows.add(new DailyRecordV1(
                    value(row, 0), value(row, 1), value(row, 2), ProviderType.fromWireValue(value(row, 3)), value(row, 4),
                    AccessMethod.fromWireValue(value(row, 5)), ProcessingStage.fromWireValue(value(row, 6)),
                    ValidationStatus.fromWireValue(value(row, 7)), value(row, 8), decodePositiveIntList(value(row, 9)), value(row, 10),
                    strictInt(value(row, 11), "calculationScale"), strictInt(value(row, 12), "displayScale"),
                    roundingMode(value(row, 13)), value(row, 14), value(row, 15), strictInt(value(row, 16), "validCount"),
                    value(row, 17), strictInt(value(row, 18), "expectedCount"), strictInt(value(row, 19), "missingCount"),
                    strictBoolean(value(row, 20), "complete"), value(row, 21), value(row, 22),
                    JsonV1Codec.decodeCompactList(value(row, 23), DailyInputRefV1.class), offsetDateTime(value(row, 24), "updatedAt")
            ));
        }
        ensureSorted(rows, DailyRecordV1.ORDER, "daily CSV rows");
        return List.copyOf(rows);
    }

    public static List<AggregateRecordV1> decodeAggregate(byte[] utf8Bytes) {
        List<CSVRecord> records = parse(utf8Bytes, AGGREGATE_HEADER, "aggregate");
        List<AggregateRecordV1> rows = new ArrayList<>();
        for (int index = 1; index < records.size(); index++) {
            CSVRecord row = records.get(index);
            requireColumnCount(row, AGGREGATE_HEADER, "aggregate");
            rows.add(new AggregateRecordV1(
                    value(row, 0), AggregateGrain.fromWireValue(value(row, 1)), value(row, 2), value(row, 3), value(row, 4),
                    ProviderType.fromWireValue(value(row, 5)), value(row, 6), AccessMethod.fromWireValue(value(row, 7)),
                    ValidationStatus.fromWireValue(value(row, 8)), value(row, 9), decodePositiveIntList(value(row, 10)), value(row, 11),
                    strictInt(value(row, 12), "calculationScale"), strictInt(value(row, 13), "displayScale"),
                    roundingMode(value(row, 14)), value(row, 15), value(row, 16), strictInt(value(row, 17), "validCount"),
                    value(row, 18), value(row, 19), value(row, 20), strictInt(value(row, 21), "expectedCount"),
                    strictInt(value(row, 22), "missingCount"), strictBoolean(value(row, 23), "complete"),
                    QualityStatus.fromWireValue(value(row, 24)), value(row, 25), value(row, 26), value(row, 27),
                    JsonV1Codec.decodeCompactList(value(row, 28), AggregateInputRefV1.class),
                    offsetDateTime(value(row, 29), "calculatedAt")
            ));
        }
        ensureSorted(rows, AggregateRecordV1.ORDER, "aggregate CSV rows");
        return List.copyOf(rows);
    }

    private static <T> List<T> canonical(List<T> rows, Comparator<T> comparator, String name) {
        if (rows == null || rows.stream().anyMatch(item -> item == null)) {
            throw new SchemaValidationException(name + " must not be null or contain null rows");
        }
        List<T> canonical = new ArrayList<>(rows);
        canonical.sort(comparator);
        for (int index = 1; index < canonical.size(); index++) {
            if (comparator.compare(canonical.get(index - 1), canonical.get(index)) == 0) {
                throw new SchemaValidationException(name + " contain duplicate frozen grouping keys");
            }
        }
        return List.copyOf(canonical);
    }

    private static List<CSVRecord> parse(byte[] utf8Bytes, List<String> expectedHeader, String type) {
        String content = decodeStrictCsvText(utf8Bytes, type);
        try (CSVParser parser = CSVParser.parse(new StringReader(content), CSVFormat.RFC4180)) {
            List<CSVRecord> records = parser.getRecords();
            if (records.isEmpty()) {
                throw new SchemaValidationException(type + " CSV must have exactly one fixed header row");
            }
            requireColumnCount(records.get(0), expectedHeader, type + " header");
            for (int index = 0; index < expectedHeader.size(); index++) {
                if (!expectedHeader.get(index).equals(records.get(0).get(index))) {
                    throw new SchemaValidationException(type + " CSV header differs from frozen v1 header at column " + index);
                }
            }
            return records;
        } catch (IOException exception) {
            throw new SchemaValidationException("Invalid RFC 4180 " + type + " CSV: " + exception.getMessage());
        }
    }

    private static String decodeStrictCsvText(byte[] utf8Bytes, String type) {
        if (utf8Bytes == null || utf8Bytes.length == 0) {
            throw new SchemaValidationException(type + " CSV must not be empty");
        }
        if (utf8Bytes.length >= 3 && (utf8Bytes[0] & 0xff) == 0xef && (utf8Bytes[1] & 0xff) == 0xbb
                && (utf8Bytes[2] & 0xff) == 0xbf) {
            throw new SchemaValidationException(type + " CSV must not include a UTF-8 BOM");
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        final String content;
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(utf8Bytes));
            content = decoded.toString();
        } catch (CharacterCodingException exception) {
            throw new SchemaValidationException(type + " CSV must be valid UTF-8");
        }
        if (!content.endsWith("\r\n") || content.endsWith("\r\n\r\n")) {
            throw new SchemaValidationException(type + " CSV must end with exactly one CRLF");
        }
        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);
            if (current == '\r' && (index + 1 == content.length() || content.charAt(index + 1) != '\n')) {
                throw new SchemaValidationException(type + " CSV must use CRLF record endings");
            }
            if (current == '\n' && (index == 0 || content.charAt(index - 1) != '\r')) {
                throw new SchemaValidationException(type + " CSV must use CRLF record endings");
            }
        }
        return content;
    }

    private static void requireColumnCount(CSVRecord row, List<String> header, String type) {
        if (row.size() != header.size()) {
            throw new SchemaValidationException(type + " has " + row.size() + " fields; expected " + header.size());
        }
    }

    private static String value(CSVRecord record, int index) {
        return record.get(index);
    }

    private static int strictInt(String value, String name) {
        if (value == null || !value.matches("0|[1-9][0-9]*")) {
            throw new SchemaValidationException(name + " must be an unsigned decimal integer");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new SchemaValidationException(name + " exceeds Java integer range");
        }
    }

    private static boolean strictBoolean(String value, String name) {
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw new SchemaValidationException(name + " must be the lowercase CSV boolean true or false");
    }

    private static RoundingMode roundingMode(String value) {
        try {
            return RoundingMode.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new SchemaValidationException("roundingMode must be a Java RoundingMode enum string");
        }
    }

    private static OffsetDateTime offsetDateTime(String value, String name) {
        try {
            return OffsetDateTime.parse(value);
        } catch (RuntimeException exception) {
            throw new SchemaValidationException(name + " must be an ISO-8601 offset datetime");
        }
    }

    private static List<Integer> decodePositiveIntList(String compactJson) {
        try {
            Integer[] parsed = JsonV1Codec.mapper().readValue(compactJson, Integer[].class);
            return Arrays.asList(parsed);
        } catch (IOException exception) {
            throw new SchemaValidationException("configVersions must be a compact JSON integer array");
        }
    }

    private static <T> void ensureSorted(List<T> rows, Comparator<T> comparator, String name) {
        for (int index = 1; index < rows.size(); index++) {
            if (comparator.compare(rows.get(index - 1), rows.get(index)) >= 0) {
                throw new SchemaValidationException(name + " must be in the frozen canonical row order without duplicates");
            }
        }
    }
}
