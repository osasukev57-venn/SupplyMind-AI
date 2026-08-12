package com.supplymind.provider.pboc;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RawAcquisitionV1;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.model.SchemaV1;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyTargetRole;
import com.supplymind.foundation.storage.DirtyTransactionType;
import com.supplymind.foundation.storage.FileDigest;
import com.supplymind.foundation.storage.FileTransactionTarget;
import com.supplymind.foundation.storage.ManifestFactory;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.RawAcquisitionStore;
import com.supplymind.foundation.storage.RawReceiptConflictException;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.provider.DataProvider;
import com.supplymind.provider.ProviderCollectOutcome;
import com.supplymind.provider.ProviderCollectRequest;
import com.supplymind.provider.ProviderSourceProfile;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * D1-T04's only concrete Provider: legal PBOC public HTML collection to immutable raw and RECEIVED+PENDING timelines.
 * DEC-056 raw-first: the detail response entity bytes are persisted as a source-level RawAcquisitionV1 before any
 * HTML decoding/parsing, so parse failures still leave verifiable source evidence. Repeat acquisition with the same
 * stable business key and the same official payload SHA-256 is an idempotent replay; a different payload for the
 * same business key fails closed with frozen conflict evidence. It never retries, validates, publishes, calculates,
 * or invokes another Provider type.
 */
public final class PbocOfficialWebDataProvider implements DataProvider {

    public static final URI ANNOUNCEMENT_LIST_URI = URI.create(
            "https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html");

    /** D3-T01 registry-unique provider identity. */
    public static final String PROVIDER_ID = "pboc-official-web";

    private static final Set<String> SUPPORTED_ITEM_IDS = Set.of(
            MonitorSeriesDefaults.USD_CNY_ITEM_ID, MonitorSeriesDefaults.EUR_CNY_ITEM_ID);

    private final DataRoot dataRoot;
    private final RawReceiptStore rawReceiptStore;
    private final RawAcquisitionStore acquisitionStore;
    private final AtomicFileStore atomicFileStore;
    private final Clock clock;
    private final PbocHttpTransport transport;
    private final PbocAnnouncementParser parser;
    private final Consumer<PbocDiagnosticEvent> diagnostics;

    public PbocOfficialWebDataProvider(
            DataRoot dataRoot,
            RawReceiptStore rawReceiptStore,
            RawAcquisitionStore acquisitionStore,
            AtomicFileStore atomicFileStore,
            Clock clock,
            PbocHttpTransport transport,
            PbocAnnouncementParser parser,
            Consumer<PbocDiagnosticEvent> diagnostics
    ) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.rawReceiptStore = Objects.requireNonNull(rawReceiptStore, "rawReceiptStore");
        this.acquisitionStore = Objects.requireNonNull(acquisitionStore, "acquisitionStore");
        this.atomicFileStore = Objects.requireNonNull(atomicFileStore, "atomicFileStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    @Override
    public ProviderSourceProfile profile() {
        return ProviderSourceProfile.of(
                PROVIDER_ID,
                ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML,
                MonitorSeriesDefaults.PBOC_SOURCE_NAME,
                ANNOUNCEMENT_LIST_URI.toString(),
                true,
                false);
    }

    @Override
    public Set<String> supportedItemIds() {
        return SUPPORTED_ITEM_IDS;
    }

    /**
     * D5-T03/F3 capability: the PBOC provider handles official-web exchange-rate targets
     * (rateKind 人民币汇率中间价). Configuration activation consults this before accepting a new
     * exchange-rate target; the decision is rateKind-driven, never itemId hard-coded.
     */
    @Override
    public boolean supports(com.supplymind.foundation.model.MonitorSeriesItemV1 item) {
        return item.providerType() == com.supplymind.foundation.model.ProviderType.OFFICIAL_WEB
                && item.accessMethod() == com.supplymind.foundation.model.AccessMethod.PUBLIC_OFFICIAL_HTML
                && com.supplymind.foundation.model.MonitorSeriesDefaults.PBOC_RATE_KIND.equals(item.rateKind());
    }

    @Override
    public ProviderCollectOutcome collect(ProviderCollectRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, String> rejected = new LinkedHashMap<>();
        for (String itemId : request.itemIds()) {
            if (!SUPPORTED_ITEM_IDS.contains(itemId)) {
                rejected.put(itemId, "UNSUPPORTED_TARGET");
            }
        }
        if (rejected.size() == request.itemIds().size()) {
            return ProviderCollectOutcome.rejectedOnly(PROVIDER_ID, rejected);
        }
        PbocCollectionResult result = collectLatestAnnouncement();
        return new ProviderCollectOutcome(
                SchemaV1.VERSION,
                PROVIDER_ID,
                result.acquisitionId(),
                result.businessDate(),
                result.payloadSha256(),
                List.of(result.usdRaw(), result.eurRaw()),
                List.of(result.usdTimeline(), result.eurTimeline()),
                rejected);
    }

    public PbocCollectionResult collectLatestAnnouncement() {
        URI currentUri = ANNOUNCEMENT_LIST_URI;
        try {
            MonitorSeriesConfigV1 config = loadActiveConfig();
            MonitorSeriesItemV1 usd = requireFrozenPbocItem(config, MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                    "USD", "1美元对人民币", "USD", "CNY/1 USD");
            MonitorSeriesItemV1 eur = requireFrozenPbocItem(config, MonitorSeriesDefaults.EUR_CNY_ITEM_ID,
                    "EUR", "1欧元对人民币", "EUR", "CNY/1 EUR");

            PbocHttpResponse listResponse = transport.get(ANNOUNCEMENT_LIST_URI);
            requireSuccessfulHtml("LIST", listResponse);
            URI detailUri = parser.discoverLatestDetailUri(listResponse.responseUri(),
                    parser.decodeHtml(listResponse.responseUri(), listResponse.entityBytes(), listResponse.contentType()));
            currentUri = detailUri;

            PbocHttpResponse detailResponse = transport.get(detailUri);
            requireSuccessfulHtml("DETAIL", detailResponse);
            OffsetDateTime receivedAt = OffsetDateTime.now(clock);
            byte[] entityBytes = detailResponse.entityBytes();
            String payloadSha256 = FileDigest.sha256(entityBytes);
            String acquisitionId = acquisitionId(detailUri, payloadSha256);

            RawAcquisitionV1 acquisition = createAcquisition(config, acquisitionId, listResponse.responseUri(),
                    detailResponse, entityBytes, payloadSha256, receivedAt);
            acquisitionStore.store(acquisition);

            PbocAnnouncement announcement = parser.parseDetail(detailUri,
                    parser.decodeHtml(detailUri, entityBytes, detailResponse.contentType()));

            RawReceiptV1 usdRaw = resolveItemRaw(config, usd, acquisition, announcement, listResponse.responseUri(),
                    detailResponse, entityBytes, payloadSha256, receivedAt);
            RawReceiptV1 eurRaw = resolveItemRaw(config, eur, acquisition, announcement, listResponse.responseUri(),
                    detailResponse, entityBytes, payloadSha256, receivedAt);

            LifecycleTimelineV1 usdTimeline = storeInitialTimelineIfMissing(usdRaw);
            LifecycleTimelineV1 eurTimeline = storeInitialTimelineIfMissing(eurRaw);

            recordDiagnostic("SUCCESS", "COMPLETE", detailResponse.responseUri(), detailResponse.statusCode(), "NONE");
            return new PbocCollectionResult(acquisitionId, listResponse.responseUri(), detailResponse.responseUri(),
                    announcement.businessDate().toString(), payloadSha256, usdRaw, eurRaw, usdTimeline, eurTimeline);
        } catch (PbocCollectionException exception) {
            recordDiagnostic(exception.failureKind().name(), exception.stage(), exception.uri(), exception.httpStatus(),
                    exception.getCause() == null ? "NONE" : exception.getCause().getClass().getSimpleName());
            throw exception;
        } catch (RuntimeException exception) {
            PbocCollectionException wrapped = new PbocCollectionException(
                    PbocCollectionFailureKind.PERSISTENCE_FAILED, "PERSISTENCE", currentUri, null,
                    "PBOC collection failed without producing a successful publishable result", exception);
            recordDiagnostic(wrapped.failureKind().name(), wrapped.stage(), wrapped.uri(), null,
                    exception.getClass().getSimpleName());
            throw wrapped;
        }
    }

    private RawAcquisitionV1 createAcquisition(
            MonitorSeriesConfigV1 config,
            String acquisitionId,
            URI listUri,
            PbocHttpResponse detailResponse,
            byte[] entityBytes,
            String payloadSha256,
            OffsetDateTime receivedAt
    ) {
        return new RawAcquisitionV1(
                SchemaV1.VERSION,
                RawAcquisitionV1.deriveAcquisitionRef(acquisitionId),
                acquisitionId,
                config.mode(),
                ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML,
                config.configVersion(),
                MonitorSeriesDefaults.PBOC_SOURCE_NAME,
                listUri.toString(),
                detailResponse.responseUri().toString(),
                detailResponse.statusCode(),
                detailResponse.contentType(),
                receivedAt,
                "base64",
                Base64.getEncoder().encodeToString(entityBytes),
                payloadSha256);
    }

    private RawReceiptV1 resolveItemRaw(
            MonitorSeriesConfigV1 config,
            MonitorSeriesItemV1 item,
            RawAcquisitionV1 acquisition,
            PbocAnnouncement announcement,
            URI listUri,
            PbocHttpResponse detailResponse,
            byte[] entityBytes,
            String payloadSha256,
            OffsetDateTime receivedAt
    ) {
        Optional<RawReceiptV1> existing = rawReceiptStore.findByBusinessKey(
                config.mode(), item.providerType(), item.itemId(), announcement.businessDate().toString());
        if (existing.isPresent()) {
            RawReceiptV1 existingReceipt = existing.get();
            if (payloadSha256.equals(existingReceipt.payloadSha256())) {
                return existingReceipt;
            }
            String conflictRef = rawReceiptStore.writeBusinessKeyConflictEvidence(
                    createRawReceipt(config, item, acquisition, announcement, listUri,
                            detailResponse, entityBytes, payloadSha256, receivedAt),
                    existingReceipt,
                    receivedAt);
            throw new PbocCollectionException(PbocCollectionFailureKind.PERSISTENCE_FAILED, "PERSISTENCE",
                    detailResponse.responseUri(), detailResponse.statusCode(),
                    "Same business key with a different official payload SHA-256; conflict evidence committed at "
                            + conflictRef,
                    new RawReceiptConflictException(conflictRef, null));
        }
        RawReceiptV1 raw = createRawReceipt(config, item, acquisition, announcement, listUri,
                detailResponse, entityBytes, payloadSha256, receivedAt);
        rawReceiptStore.store(raw);
        return raw;
    }

    private MonitorSeriesConfigV1 loadActiveConfig() {
        String activeRef = DataPaths.configActiveRef();
        Path activePath = dataRoot.resolveDataRef(activeRef);
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(activeRef));
        if (!ManifestVerifier.matches(dataRoot, activeRef, activePath, manifestPath, List.of())) {
            throw new PbocCollectionException(PbocCollectionFailureKind.CONFIG_REJECTED, "CONFIG", null, null,
                    "PBOC collection requires a valid active monitor-series configuration and manifest");
        }
        try {
            return JsonV1Codec.decodeFile(Files.readAllBytes(activePath), MonitorSeriesConfigV1.class);
        } catch (IOException | RuntimeException exception) {
            throw new PbocCollectionException(PbocCollectionFailureKind.CONFIG_REJECTED, "CONFIG", null, null,
                    "PBOC collection cannot read the active monitor-series configuration", exception);
        }
    }

    private static MonitorSeriesItemV1 requireFrozenPbocItem(
            MonitorSeriesConfigV1 config, String itemId, String expectedExternalCode, String expectedAnchor,
            String expectedBaseCurrency, String expectedUnit
    ) {
        MonitorSeriesItemV1 item;
        try {
            item = config.requireItem(itemId);
        } catch (RuntimeException exception) {
            throw new PbocCollectionException(PbocCollectionFailureKind.CONFIG_REJECTED, "CONFIG", null, null,
                    "PBOC collection requires both frozen EUR/CNY and USD/CNY items", exception);
        }
        boolean valid = item.enabled()
                && "PBOC".equals(item.sourceIntent())
                && item.providerType() == ProviderType.OFFICIAL_WEB
                && item.accessMethod() == AccessMethod.PUBLIC_OFFICIAL_HTML
                && MonitorSeriesDefaults.PBOC_SOURCE_NAME.equals(item.actualSourceName())
                && item.routeDecision() == RouteDecision.PRIMARY
                && expectedExternalCode.equals(item.externalCode())
                && expectedAnchor.equals(item.sourceFieldKey())
                && expectedBaseCurrency.equals(item.baseCurrency())
                && "CNY".equals(item.currency())
                && expectedUnit.equals(item.unit());
        if (!valid) {
            throw new PbocCollectionException(PbocCollectionFailureKind.CONFIG_REJECTED, "CONFIG", null, null,
                    "Active monitor-series configuration does not match the frozen PBOC item contract");
        }
        return item;
    }

    private RawReceiptV1 createRawReceipt(
            MonitorSeriesConfigV1 config, MonitorSeriesItemV1 item, RawAcquisitionV1 acquisition,
            PbocAnnouncement announcement, URI listUri, PbocHttpResponse detailResponse, byte[] entityBytes,
            String payloadSha256, OffsetDateTime receivedAt
    ) {
        String runId = runId(item.externalCode(), announcement.businessDate().toString(), payloadSha256);
        String rawRef = RawReceiptV1.deriveRawRef(config.mode(), item.providerType(), item.itemId(), receivedAt, runId);
        String rawValue = "USD".equals(item.externalCode()) ? announcement.usdRawValue() : announcement.eurRawValue();
        String sourceReference = "PBOC公告列表=" + listUri + ";公告标题=" + announcement.title();
        return new RawReceiptV1(
                SchemaV1.VERSION, rawRef, acquisition.acquisitionId(), runId, config.mode(), item.providerType(),
                item.accessMethod(), config.configVersion(), item.actualSourceName(),
                detailResponse.responseUri().toString(), sourceReference, item.itemId(),
                announcement.titleBusinessDateRaw(), announcement.businessDate().toString(),
                announcement.sourcePublishedAtRaw(), announcement.sourcePublishedAt(), receivedAt, null, rawValue,
                item.unit(), item.currency(), null, detailResponse.statusCode(), detailResponse.contentType(), "base64",
                Base64.getEncoder().encodeToString(entityBytes), payloadSha256, item.sourceFieldKey(), receivedAt,
                acquisition.acquisitionRef(), null);
    }

    private LifecycleTimelineV1 storeInitialTimelineIfMissing(RawReceiptV1 raw) {
        Path stagingPath = dataRoot.resolveDataRef(DataPaths.stagingRef(raw.runId()));
        if (Files.isRegularFile(stagingPath)) {
            return JsonV1Codec.decodeFile(readAllBytes(stagingPath), LifecycleTimelineV1.class);
        }
        return storeInitialTimeline(raw);
    }

    private static byte[] readAllBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + path, exception);
        }
    }

    private LifecycleTimelineV1 storeInitialTimeline(RawReceiptV1 raw) {
        String recordId = "record-" + raw.runId();
        LifecycleTimelineV1 timeline = LifecycleTimelineV1.initial(recordId, raw.runId(), raw.rawRef(), raw.receivedAt());
        String stagingRef = DataPaths.stagingRef(raw.runId());
        byte[] dataBytes = JsonV1Codec.encodeFile(timeline);
        ManifestV1 manifest = ManifestFactory.json(stagingRef, dataBytes, List.of(raw.runId()), raw.receivedAt());
        byte[] manifestBytes = JsonV1Codec.encodeFile(manifest);
        FileTransactionTarget target = new FileTransactionTarget(DirtyTargetRole.BUSINESS_FILE, stagingRef,
                dataBytes, manifestBytes, false);
        atomicFileStore.commit("timeline-" + raw.runId(), DirtyTransactionType.SINGLE_FILE, raw.receivedAt(), List.of(target));
        return timeline;
    }

    private static void requireSuccessfulHtml(String stage, PbocHttpResponse response) {
        URI uri = response.responseUri();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new PbocCollectionException(PbocCollectionFailureKind.HTTP_REJECTED, stage, uri, response.statusCode(),
                    "PBOC response status is not successful");
        }
        String contentType = response.contentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("text/html")) {
            throw new PbocCollectionException(PbocCollectionFailureKind.CONTENT_TYPE_REJECTED, stage, uri, response.statusCode(),
                    "PBOC response Content-Type is not text/html");
        }
    }

    private static String acquisitionId(URI detailUri, String payloadSha256) {
        String announcementSegment = announcementSegment(detailUri);
        return "pboc-acq-" + announcementSegment + "-" + payloadSha256;
    }

    private static String announcementSegment(URI detailUri) {
        String path = detailUri.getRawPath();
        if (path == null || path.isBlank()) {
            return "unknown";
        }
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int lastSlash = trimmed.lastIndexOf('/');
        String last = lastSlash < 0 ? trimmed : trimmed.substring(lastSlash + 1);
        if (!"index.html".equals(last) && last.endsWith(".html")) {
            return last.substring(0, last.length() - ".html".length());
        }
        if (lastSlash > 0) {
            String parent = trimmed.substring(0, lastSlash);
            int parentSlash = parent.lastIndexOf('/');
            return parentSlash < 0 ? parent : parent.substring(parentSlash + 1);
        }
        return "unknown";
    }

    private static String runId(String externalCode, String businessDate, String payloadSha256) {
        return "pboc-" + externalCode.toLowerCase(Locale.ROOT) + "-" + businessDate.replace("-", "") + "-" + payloadSha256;
    }

    private void recordDiagnostic(String outcome, String stage, URI uri, Integer httpStatus, String exceptionType) {
        try {
            diagnostics.accept(new PbocDiagnosticEvent(outcome, stage, sanitizeUri(uri), httpStatus, exceptionType));
        } catch (RuntimeException ignored) {
            // Diagnostics must not manufacture success or change file persistence semantics.
        }
    }

    private static String sanitizeUri(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) { return "unavailable"; }
        int port = uri.getPort();
        String authority = uri.getHost() + (port < 0 ? "" : ":" + port);
        String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + authority + path;
    }
}