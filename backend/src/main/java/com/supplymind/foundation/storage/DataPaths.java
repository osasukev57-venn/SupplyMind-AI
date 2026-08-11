package com.supplymind.foundation.storage;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Canonical dataRoot-relative path construction and strict path-reference validation. */
public final class DataPaths {

    public static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    public static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern YEAR_MONTH = Pattern.compile("[0-9]{4}-(0[1-9]|1[0-2])");
    private static final Pattern YEAR = Pattern.compile("[0-9]{4}");
    private static final Pattern MONTH = Pattern.compile("0[1-9]|1[0-2]");
    private static final List<String> MODES = List.of("formal", "demo", "test");
    private static final List<String> PROVIDER_TYPES = List.of(
            "official_web", "authorized_api", "free_public", "manual", "local_import", "synthetic_demo");

    private DataPaths() {
    }

    public static String rawRef(String mode, String providerType, String itemId, OffsetDateTime receivedAt, String runId) {
        requireMode(mode);
        requireProviderType(providerType);
        requireIdentifier(itemId, "itemId");
        requireIdentifier(runId, "runId");
        YearMonth partition = shanghaiYearMonth(receivedAt);
        return "raw/" + mode + "/" + providerType + "/" + itemId + "/"
                + partition.getYear() + "/" + String.format("%02d", partition.getMonthValue()) + "/" + runId + ".json";
    }

    public static String stagingRef(String runId) {
        requireIdentifier(runId, "runId");
        return "staging/" + runId + ".json";
    }

    /** DEC-056 source-level raw acquisition evidence path, derived from the acquisitionId. */
    public static String acquisitionRef(String acquisitionId) {
        requireIdentifier(acquisitionId, "acquisitionId");
        return "raw/source/" + acquisitionId + ".json";
    }

    /** D3-T05 source-level immutable import file evidence path, derived from the importId. */
    public static String importRef(String importId) {
        requireIdentifier(importId, "importId");
        return "raw/import/" + importId + ".json";
    }

    public static String quarantineRef(String itemId, OffsetDateTime receivedAt, String runId) {
        requireIdentifier(itemId, "itemId");
        requireIdentifier(runId, "runId");
        return "quarantine/" + itemId + "/" + shanghaiYearMonth(receivedAt) + "/" + runId + ".json";
    }

    public static String configActiveRef() {
        return "config/monitor-series.json";
    }

    public static String configHistoryRef(int configVersion) {
        if (configVersion < 1) {
            throw new StorageException("configVersion must be a positive integer");
        }
        return "config/history/" + configVersion + ".json";
    }

    public static String dailyRef(String itemId, YearMonth businessMonth) {
        requireIdentifier(itemId, "itemId");
        Objects.requireNonNull(businessMonth, "businessMonth");
        return "processed/daily/" + itemId + "/" + businessMonth + ".csv";
    }

    public static String aggregateRef(String itemId, String grain, int year) {
        requireIdentifier(itemId, "itemId");
        if (!List.of("month", "quarter", "halfyear", "year").contains(grain)) {
            throw new StorageException("Unsupported aggregate grain: " + grain);
        }
        if (year < 1000 || year > 9999) {
            throw new StorageException("Aggregate year must be four digits: " + year);
        }
        return "processed/aggregate/" + itemId + "/" + grain + "/" + year + ".csv";
    }

    public static String rawConflictRef(String itemId, OffsetDateTime receivedAt, String runId, String conflictId) {        requireIdentifier(itemId, "itemId");
        requireIdentifier(runId, "runId");
        requireIdentifier(conflictId, "conflictId");
        return "runtime/conflicts/raw/" + itemId + "/" + shanghaiYearMonth(receivedAt)
                + "/" + runId + "/" + conflictId + ".json";
    }

    public static String manifestRef(String businessDataRef) {
        requireLegalDataRef(businessDataRef);
        if (businessDataRef.endsWith(".manifest.json")) {
            throw new StorageException("A manifest must not receive its own manifest: " + businessDataRef);
        }
        return businessDataRef + ".manifest.json";
    }

    public static String dirtyMarkerRef(String transactionId) {
        requireIdentifier(transactionId, "transactionId");
        return "runtime/dirty/" + transactionId + ".json";
    }

    public static String dirtyMarkerTemporaryRef(String transactionId) {
        requireIdentifier(transactionId, "transactionId");
        return "runtime/dirty/." + transactionId + ".json.marker.tmp";
    }

    public static String dirtyMarkerBackupRef(String transactionId) {
        requireIdentifier(transactionId, "transactionId");
        return "runtime/dirty/." + transactionId + ".json.marker.bak";
    }

    public static String adjacentTemporaryFileName(String targetFileName, String transactionId) {
        requireIdentifier(transactionId, "transactionId");
        if (targetFileName == null || targetFileName.isBlank() || targetFileName.contains("/") || targetFileName.contains("\\")) {
            throw new StorageException("A target filename must be a plain filename");
        }
        return "." + targetFileName + "." + transactionId + ".tmp";
    }

    public static String adjacentBackupFileName(String targetFileName, String transactionId) {
        requireIdentifier(transactionId, "transactionId");
        if (targetFileName == null || targetFileName.isBlank() || targetFileName.contains("/") || targetFileName.contains("\\")) {
            throw new StorageException("A target filename must be a plain filename");
        }
        return "." + targetFileName + "." + transactionId + ".bak";
    }

    public static void requireLegalDataRef(String reference) {
        requireSafeRelativePath(reference);
        if (reference.endsWith(".manifest.json")) {
            String businessRef = reference.substring(0, reference.length() - ".manifest.json".length());
            if (!isBusinessDataRef(businessRef)) {
                throw new StorageException("Manifest reference does not name a legal business target: " + reference);
            }
            return;
        }
        if (!isBusinessDataRef(reference)) {
            throw new StorageException("Unsupported dataRoot-relative reference: " + reference);
        }
    }

    public static void requireSafeRelativePath(String reference) {
        if (reference == null || reference.isBlank() || reference.indexOf('\\') >= 0
                || reference.startsWith("/") || reference.startsWith("~")
                || reference.matches("^[A-Za-z]:.*") || reference.contains("//")) {
            throw new StorageException("Reference must be a non-empty slash-separated relative path: " + reference);
        }
        for (String segment : reference.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new StorageException("Reference contains an unsafe path segment: " + reference);
            }
        }
    }

    public static void requireIdentifier(String value, String fieldName) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new StorageException(fieldName + " must match " + IDENTIFIER.pattern());
        }
    }

    public static void requireMode(String mode) {
        if (!MODES.contains(mode)) {
            throw new StorageException("Unsupported mode: " + mode);
        }
    }

    public static void requireProviderType(String providerType) {
        if (!PROVIDER_TYPES.contains(providerType)) {
            throw new StorageException("Unsupported providerType: " + providerType);
        }
    }

    private static YearMonth shanghaiYearMonth(OffsetDateTime at) {
        Objects.requireNonNull(at, "receivedAt");
        return YearMonth.from(at.atZoneSameInstant(SHANGHAI));
    }

    private static boolean isBusinessDataRef(String ref) {
        String[] segments = ref.split("/");
        if (ref.equals("config/monitor-series.json")) {
            return true;
        }
        if (segments.length == 3 && segments[0].equals("config") && segments[1].equals("history")
                && segments[2].matches("[1-9][0-9]*\\.json")) {
            return true;
        }
        if (segments.length == 6 && segments[0].equals("raw") && MODES.contains(segments[1])
                && PROVIDER_TYPES.contains(segments[2]) && IDENTIFIER.matcher(segments[3]).matches()
                && YEAR.matcher(segments[4]).matches() && segments[5].matches("(0[1-9]|1[0-2])/.+")) {
            // unreachable for slash split; retained only to make the raw branch explicit below
            return false;
        }
        if (segments.length == 7 && segments[0].equals("raw") && MODES.contains(segments[1])
                && PROVIDER_TYPES.contains(segments[2]) && IDENTIFIER.matcher(segments[3]).matches()
                && YEAR.matcher(segments[4]).matches() && MONTH.matcher(segments[5]).matches()
                && segments[6].matches("[A-Za-z0-9._-]+\\.json")) {
            return true;
        }
        if (segments.length == 3 && segments[0].equals("raw") && segments[1].equals("source")
                && segments[2].matches("[A-Za-z0-9._-]+\\.json")) {
            return true;
        }
        if (segments.length == 3 && segments[0].equals("raw") && segments[1].equals("import")
                && segments[2].matches("[A-Za-z0-9._-]+\\.json")) {
            return true;
        }
        if (segments.length == 2 && segments[0].equals("staging") && segments[1].matches("[A-Za-z0-9._-]+\\.json")) {
            return true;
        }
        if (segments.length == 4 && segments[0].equals("quarantine") && IDENTIFIER.matcher(segments[1]).matches()
                && YEAR_MONTH.matcher(segments[2]).matches() && segments[3].matches("[A-Za-z0-9._-]+\\.json")) {
            return true;
        }
        if (segments.length == 4 && segments[0].equals("processed") && segments[1].equals("daily")
                && IDENTIFIER.matcher(segments[2]).matches() && YEAR_MONTH.matcher(segments[3].replace(".csv", "")).matches()
                && segments[3].endsWith(".csv")) {
            return true;
        }
        if (segments.length == 5 && segments[0].equals("processed") && segments[1].equals("aggregate")
                && IDENTIFIER.matcher(segments[2]).matches()
                && List.of("month", "quarter", "halfyear", "year").contains(segments[3])
                && segments[4].matches("[0-9]{4}\\.csv")) {
            return true;
        }
        if (segments.length == 3 && (segments[0].equals("warning") || segments[0].equals("report"))
                && YEAR_MONTH.matcher(segments[1]).matches() && segments[2].matches("[A-Za-z0-9._-]+\\.json")) {
            return true;
        }
        if (segments.length == 4 && segments[0].equals("runtime") && segments[1].equals("jobs")
                && segments[2].equals("active") && segments[3].matches("[A-Za-z0-9._-]+\\.json")) {
            return true;
        }
        if (segments.length == 5 && segments[0].equals("runtime") && segments[1].equals("jobs")
                && segments[2].equals("history") && YEAR_MONTH.matcher(segments[3]).matches()
                && segments[4].matches("[A-Za-z0-9._-]+\\.json")) {
            return true;
        }
        if (segments.length == 3 && segments[0].equals("runtime") && segments[1].equals("dirty")
                && segments[2].matches("[A-Za-z0-9._-]+\\.json")) {
            return true;
        }
        return segments.length == 7 && segments[0].equals("runtime") && segments[1].equals("conflicts")
                && segments[2].equals("raw") && IDENTIFIER.matcher(segments[3]).matches()
                && YEAR_MONTH.matcher(segments[4]).matches() && IDENTIFIER.matcher(segments[5]).matches()
                && segments[6].matches("[A-Za-z0-9._-]+\\.json");
    }
}
