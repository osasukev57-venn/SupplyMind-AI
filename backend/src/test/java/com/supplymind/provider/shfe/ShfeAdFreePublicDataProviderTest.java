package com.supplymind.provider.shfe;

import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RawAcquisitionV1;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.FileDigest;
import com.supplymind.foundation.storage.QuarantineStore;
import com.supplymind.foundation.storage.RawAcquisitionStore;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.processing.AggregateProcessingService;
import com.supplymind.processing.DailyProcessingService;
import com.supplymind.provider.ProviderCollectRequest;
import com.supplymind.publish.LifecyclePublishService;
import com.supplymind.validation.LifecycleValidationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShfeAdFreePublicDataProviderTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-21T04:00:00Z"), ZoneOffset.ofHours(8));
    private static final byte[] TRADING_DAY =
            "{\"currentTradingday\":\"20260821\",\"lastTradingday\":\"20260820\"}"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] DAILY = ("{\"o_curinstrument\":["
            + "{\"PRODUCTID\":\"ad_f\",\"PRODUCTNAME\":\"铸造铝合金\","
            + "\"DELIVERYMONTH\":\"2609\",\"SETTLEMENTPRICE\":\"22920\",\"VOLUME\":\"877\"},"
            + "{\"PRODUCTID\":\"ad_f\",\"PRODUCTNAME\":\"铸造铝合金\","
            + "\"DELIVERYMONTH\":\"2610\",\"SETTLEMENTPRICE\":\"22970\",\"VOLUME\":\"6730\"},"
            + "{\"PRODUCTID\":\"ad_f\",\"DELIVERYMONTH\":\"小计\","
            + "\"SETTLEMENTPRICE\":\"\",\"VOLUME\":\"7607\"}]}"
    ).getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temporaryDirectory;

    @Test
    void publicShfeAdc12RunsThroughFormalRawValidationPublishDailyAndAggregate() throws Exception {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("shfe free public"));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore files = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configs = new ConfigActivationStore(root, files, CLOCK);
        configs.ensureInitialDefault();
        RawReceiptStore rawStore = new RawReceiptStore(root, files, CLOCK);
        ShfeAdFreePublicDataProvider provider = new ShfeAdFreePublicDataProvider(
                configs, rawStore, CLOCK, new StubTransport(), new ShfeDailyMarketParser());

        String itemId = com.supplymind.foundation.model.MonitorSeriesDefaults.ADC12_SMM_ITEM_ID;
        String secondItemId = com.supplymind.foundation.model.MonitorSeriesDefaults.ADC12_AM_ITEM_ID;
        var outcome = provider.collect(ProviderCollectRequest.current(java.util.List.of(itemId, secondItemId)));

        assertEquals("2026-08-20", outcome.businessDate());
        assertEquals(2, outcome.raws().size());
        RawReceiptV1 raw = outcome.raws().stream().filter(value -> value.itemId().equals(itemId)).findFirst().orElseThrow();
        RawReceiptV1 secondRaw = outcome.raws().stream().filter(value -> value.itemId().equals(secondItemId)).findFirst().orElseThrow();
        assertEquals(raw.acquisitionId(), secondRaw.acquisitionId(), "one HTTP entity shared by both configured ADC12 intents");
        assertEquals(raw.payloadSha256(), secondRaw.payloadSha256());
        org.junit.jupiter.api.Assertions.assertNotEquals(raw.runId(), secondRaw.runId());
        org.junit.jupiter.api.Assertions.assertNotEquals(raw.rawRef(), secondRaw.rawRef());
        assertEquals(Mode.FORMAL, raw.mode());
        assertEquals(ProviderType.FREE_PUBLIC, raw.providerType());
        assertEquals(AccessMethod.FREE_PUBLIC_WEB, raw.accessMethod());
        assertEquals("22970", raw.rawValue(), "highest-volume AD contract settlement is selected");
        assertEquals("元/吨", raw.rawUnit());
        assertEquals(ShfeAdFreePublicDataProvider.SOURCE_NAME, raw.actualSourceName());
        assertEquals(FileDigest.sha256(DAILY), raw.payloadSha256());
        assertEquals(DAILY.length, Base64.getDecoder().decode(raw.payloadBase64()).length,
                "the complete HTTP entity bytes are retained");

        RawAcquisitionV1 acquisition = new RawAcquisitionV1(
                "1.0", DataPaths.acquisitionRef(raw.acquisitionId()), raw.acquisitionId(), raw.mode(),
                raw.providerType(), raw.accessMethod(), raw.configVersion(), raw.actualSourceName(),
                raw.sourceUrl(), raw.sourceUrl(), raw.httpStatus(), raw.contentType(), raw.receivedAt(),
                "base64", raw.payloadBase64(), raw.payloadSha256());
        new RawAcquisitionStore(root, files, CLOCK).store(acquisition);
        rawStore.store(raw);
        TimelineStore timelines = new TimelineStore(root, files, CLOCK);
        timelines.createInitial(raw.runId(), raw.rawRef(), raw.receivedAt());
        new LifecycleValidationService(root, timelines, CLOCK).process(raw.runId());
        new LifecyclePublishService(root, timelines, new QuarantineStore(root, files, CLOCK), CLOCK)
                .process(raw.runId());
        var published = timelines.read(raw.runId()).current();
        assertEquals(ProcessingStage.PUBLISHED, published.processingStage());
        assertTrue(published.validationStatus() == ValidationStatus.VERIFIED
                || published.validationStatus() == ValidationStatus.VERIFIED_WITH_NOTICE);

        YearMonth month = YearMonth.of(2026, 8);
        var daily = new DailyProcessingService(root, timelines, files, CLOCK).processMonth(itemId, month);
        assertEquals("22970.00", daily.rows().get(0).avg());
        var aggregates = new AggregateProcessingService(root, files, CLOCK).processYear(itemId, 2026);
        assertEquals(4, aggregates.writtenRefs().size());
        assertTrue(Files.isRegularFile(root.resolveDataRef(DataPaths.dailyRef(itemId, month))));
    }

    private static final class StubTransport implements ShfeHttpTransport {
        @Override
        public ShfeHttpResponse get(URI uri) {
            byte[] body = uri.equals(ShfeAdFreePublicDataProvider.CURRENT_TRADING_DAY_URI)
                    ? TRADING_DAY : DAILY;
            return new ShfeHttpResponse(uri, 200, "application/json", body);
        }
    }
}
