package com.supplymind.processing;

import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AggregateGrain;
import com.supplymind.foundation.model.AggregateRecordV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyTargetRole;
import com.supplymind.foundation.storage.DirtyTransactionType;
import com.supplymind.foundation.storage.FileTransactionTarget;
import com.supplymind.foundation.storage.ManifestFactory;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.StorageException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * D2-T04 aggregate persistence: every grain (month/quarter/halfyear/year) is rebuilt directly
 * from the formal daily CSVs of its own period (never from lower-level aggregate files) and
 * atomically written at processed/aggregate/&lt;itemId&gt;/{month,quarter,halfyear,year}/YYYY.csv
 * with an adjacent manifest. Empty periods produce no file.
 */
public final class AggregateProcessingService {

    private final DataRoot dataRoot;
    private final AtomicFileStore fileStore;
    private final Clock clock;

    public AggregateProcessingService(DataRoot dataRoot, AtomicFileStore fileStore, Clock clock) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.fileStore = Objects.requireNonNull(fileStore, "fileStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AggregateYearResult processYear(String itemId, int year) {
        Objects.requireNonNull(itemId, "itemId");
        List<String> writtenRefs = new ArrayList<>();
        List<AggregateGrain> grains = List.of(
                AggregateGrain.MONTH, AggregateGrain.QUARTER, AggregateGrain.HALFYEAR, AggregateGrain.YEAR);
        for (AggregateGrain grain : grains) {
            writtenRefs.addAll(processGrain(itemId, grain, year));
        }
        return new AggregateYearResult(itemId, year, List.copyOf(writtenRefs));
    }

    public List<String> processGrain(String itemId, AggregateGrain grain, int year) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(grain, "grain");
        List<AggregateRecordV1> allRows = new ArrayList<>();
        Set<String> sourceRunIds = new LinkedHashSet<>();
        for (int index = 1; index <= periodCount(grain); index++) {
            LocalDate periodStart = PeriodBoundaries.periodStart(grain, year, index);
            LocalDate periodEnd = PeriodBoundaries.periodEnd(grain, year, index);
            List<AggregateInput> inputs = loadPeriodDailyInputs(itemId, periodStart, periodEnd);
            if (inputs.isEmpty()) {
                continue;
            }
            allRows.addAll(AggregateCalculator.calculate(
                    grain, periodStart.toString(), periodEnd.toString(), inputs));
            for (AggregateInput input : inputs) {
                sourceRunIds.addAll(readDailySourceRunIds(input.dailyFileRef()));
            }
        }
        if (allRows.isEmpty()) {
            return List.of();
        }
        byte[] csvBytes = CsvV1Codec.encodeAggregate(allRows);
        String aggregateRef = DataPaths.aggregateRef(itemId, grain.wireValue(), year);
        String minPeriodStart = allRows.stream().map(AggregateRecordV1::periodStart)
                .min(String::compareTo).orElseThrow();
        String maxPeriodEnd = allRows.stream().map(AggregateRecordV1::periodEnd)
                .max(String::compareTo).orElseThrow();
        OffsetDateTime generatedAt = OffsetDateTime.now(clock);
        ManifestV1 manifest = ManifestFactory.csv(
                aggregateRef, csvBytes, allRows.size(), minPeriodStart, maxPeriodEnd,
                new ArrayList<>(sourceRunIds), generatedAt);
        byte[] manifestBytes = JsonV1Codec.encodeFile(manifest);
        fileStore.commit(
                "aggregate-" + itemId + "-" + grain.wireValue() + "-" + year,
                DirtyTransactionType.SINGLE_FILE,
                generatedAt,
                List.of(new FileTransactionTarget(
                        DirtyTargetRole.BUSINESS_FILE, aggregateRef, csvBytes, manifestBytes, false)));
        return List.of(aggregateRef);
    }

    private List<AggregateInput> loadPeriodDailyInputs(String itemId, LocalDate periodStart, LocalDate periodEnd) {
        List<AggregateInput> inputs = new ArrayList<>();
        YearMonth current = YearMonth.from(periodStart);
        YearMonth last = YearMonth.from(periodEnd);
        while (!current.isAfter(last)) {
            String dailyRef = DataPaths.dailyRef(itemId, current);
            Path dailyPath = dataRoot.resolveDataRef(dailyRef);
            Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(dailyRef));
            if (Files.isRegularFile(dailyPath)) {
                if (!ManifestVerifier.matches(dataRoot, dailyRef, dailyPath, manifestPath)) {
                    throw new StorageException("Aggregate requires a manifest-valid daily file: " + dailyRef);
                }
                String fileSha256 = readDailyFileSha256(manifestPath);
                try {
                    List<DailyRecordV1> rows = CsvV1Codec.decodeDaily(Files.readAllBytes(dailyPath));
                    for (DailyRecordV1 row : rows) {
                        inputs.add(new AggregateInput(row, dailyRef, fileSha256));
                    }
                } catch (IOException exception) {
                    throw new StorageException("Unable to read daily file " + dailyRef, exception);
                }
            }
            current = current.plusMonths(1);
        }
        return List.copyOf(inputs);
    }

    private String readDailyFileSha256(Path manifestPath) {
        try {
            return JsonV1Codec.decodeFile(Files.readAllBytes(manifestPath), ManifestV1.class).fileSha256();
        } catch (IOException exception) {
            throw new StorageException("Unable to read daily manifest " + manifestPath, exception);
        }
    }

    private List<String> readDailySourceRunIds(String dailyRef) {
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(dailyRef));
        if (!Files.isRegularFile(manifestPath)) {
            throw new StorageException("Aggregate requires the daily adjacent manifest: " + dailyRef);
        }
        try {
            return JsonV1Codec.decodeFile(Files.readAllBytes(manifestPath), ManifestV1.class).sourceRunIds();
        } catch (IOException exception) {
            throw new StorageException("Unable to read daily manifest for " + dailyRef, exception);
        }
    }

    private static int periodCount(AggregateGrain grain) {
        switch (grain) {
            case MONTH:
                return 12;
            case QUARTER:
                return 4;
            case HALFYEAR:
                return 2;
            case YEAR:
                return 1;
            default:
                throw new IllegalArgumentException("Unsupported grain: " + grain);
        }
    }

    public record AggregateYearResult(String itemId, int year, List<String> writtenRefs) {
    }
}
