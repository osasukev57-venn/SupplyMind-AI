package com.supplymind.provider.shfe;

import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.model.SchemaV1;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.FileDigest;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.StorageException;
import com.supplymind.provider.CollectionMode;
import com.supplymind.provider.DataProvider;
import com.supplymind.provider.ProviderCollectOutcome;
import com.supplymind.provider.ProviderCollectRequest;
import com.supplymind.provider.ProviderSourceProfile;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Public, credential-free ADC12-equivalent benchmark from Shanghai Futures Exchange.
 *
 * <p>The source is deliberately labelled as the SHFE cast-aluminium-alloy futures main-contract
 * settlement benchmark. It is not represented as SMM/Asian-Metal spot data. The complete public
 * daily JSON entity is retained in every RawReceipt and the configured commercial source intent
 * remains visible through sourceIntent/fallbackReason.</p>
 */
public final class ShfeAdFreePublicDataProvider implements DataProvider {
    public static final String PROVIDER_ID = "shfe-ad-free-public";
    public static final String SOURCE_NAME = "上海期货交易所铸造铝合金期货主力合约结算价（公开基准）";
    public static final String FALLBACK_REASON = "指定商业现货源未获授权，使用上期所ADC12等价交割品期货公开基准";
    public static final URI CURRENT_TRADING_DAY_URI = URI.create(
            "https://www.shfe.com.cn/data/config/currentTradingday.dat");
    private static final String DAILY_PREFIX =
            "https://www.shfe.com.cn/data/tradedata/future/dailydata/kx";
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final ConfigActivationStore configs;
    private final RawReceiptStore rawStore;
    private final Clock clock;
    private final ShfeHttpTransport transport;
    private final ShfeDailyMarketParser parser;

    public ShfeAdFreePublicDataProvider(
            ConfigActivationStore configs,
            RawReceiptStore rawStore,
            Clock clock,
            ShfeHttpTransport transport,
            ShfeDailyMarketParser parser
    ) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.rawStore = Objects.requireNonNull(rawStore, "rawStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    @Override
    public ProviderSourceProfile profile() {
        return ProviderSourceProfile.of(PROVIDER_ID, ProviderType.FREE_PUBLIC,
                AccessMethod.FREE_PUBLIC_WEB, SOURCE_NAME,
                "https://www.shfe.com.cn/reports/tradedata/dailyandweeklydata/", true, true);
    }

    @Override
    public Set<String> supportedItemIds() {
        try {
            return configs.readActiveConfig().items().stream()
                    .filter(MonitorSeriesItemV1::enabled)
                    .filter(this::supports)
                    .map(MonitorSeriesItemV1::itemId)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException exception) {
            return Set.of();
        }
    }

    @Override
    public boolean supports(MonitorSeriesItemV1 item) {
        return item.providerType() == ProviderType.FREE_PUBLIC
                && item.accessMethod() == AccessMethod.FREE_PUBLIC_WEB
                && item.routeDecision() == RouteDecision.FALLBACK_FREE_PUBLIC
                && "material".equals(item.rateKind())
                && "ADC12".equalsIgnoreCase(item.externalCode())
                && item.materialValidation() != null
                && "ADC12".equalsIgnoreCase(item.materialValidation().canonicalSpecCode())
                && "CNY".equals(item.currency())
                && "元/吨".equals(item.unit());
    }

    @Override
    public ProviderCollectOutcome collect(ProviderCollectRequest request) {
        Objects.requireNonNull(request, "request");
        MonitorSeriesConfigV1 config = configs.readActiveConfig();
        Map<String, String> rejected = new LinkedHashMap<>();
        List<MonitorSeriesItemV1> targets = new ArrayList<>();
        for (String itemId : request.itemIds()) {
            MonitorSeriesItemV1 item;
            try {
                item = config.requireItem(itemId);
            } catch (RuntimeException exception) {
                rejected.put(itemId, "UNSUPPORTED_TARGET");
                continue;
            }
            if (!item.enabled() || !supports(item)) {
                rejected.put(itemId, "UNSUPPORTED_TARGET");
            } else {
                targets.add(item);
            }
        }
        if (targets.isEmpty()) {
            return ProviderCollectOutcome.rejectedOnly(PROVIDER_ID, rejected);
        }

        LocalDate date = request.collectionMode() == CollectionMode.CURRENT
                ? fetchLastCompletedTradingDay()
                : request.historyStartDate();
        URI dailyUri = dailyUri(date);
        ShfeHttpResponse response = requireJson(transport.get(dailyUri));
        ShfeDailyMarketParser.AdSettlement settlement =
                parser.parseAdMainSettlement(date, response.entityBytes());
        OffsetDateTime receivedAt = OffsetDateTime.now(clock);
        byte[] payload = response.entityBytes();
        String payloadSha = FileDigest.sha256(payload);
        String targetSetSha = FileDigest.sha256(targets.stream().map(MonitorSeriesItemV1::itemId)
                .sorted().collect(Collectors.joining(",")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String acquisitionId = "shfe-ad-acq-" + date.format(BASIC_DATE) + "-"
                + payloadSha.substring(0, 16) + "-" + targetSetSha.substring(0, 16);
        List<RawReceiptV1> raws = new ArrayList<>();
        for (MonitorSeriesItemV1 item : targets) {
            RawReceiptV1 incoming = raw(config, item, settlement, response,
                    receivedAt, payload, payloadSha, acquisitionId);
            var existing = rawStore.findByBusinessKey(config.mode(), ProviderType.FREE_PUBLIC,
                    item.itemId(), date.toString());
            if (existing.isPresent()) {
                if (payloadSha.equals(existing.get().payloadSha256())) {
                    raws.add(existing.get());
                    continue;
                }
                String conflictRef = rawStore.writeBusinessKeyConflictEvidence(
                        incoming, existing.get(), receivedAt);
                throw new StorageException("SHFE business-key payload changed; conflict evidence: " + conflictRef);
            }
            raws.add(incoming);
        }
        return new ProviderCollectOutcome(SchemaV1.VERSION, PROVIDER_ID, acquisitionId,
                date.toString(), payloadSha, raws, List.of(), rejected);
    }

    private LocalDate fetchLastCompletedTradingDay() {
        ShfeHttpResponse response = requireJson(transport.get(CURRENT_TRADING_DAY_URI));
        return parser.lastCompletedTradingDay(response.entityBytes());
    }

    private static RawReceiptV1 raw(
            MonitorSeriesConfigV1 config,
            MonitorSeriesItemV1 item,
            ShfeDailyMarketParser.AdSettlement settlement,
            ShfeHttpResponse response,
            OffsetDateTime receivedAt,
            byte[] payload,
            String payloadSha,
            String acquisitionId
    ) {
        String dateToken = settlement.businessDate().format(BASIC_DATE);
        String runId = "shfe-ad-" + item.itemId().toLowerCase(Locale.ROOT).replace('.', '-')
                + "-" + dateToken + "-" + payloadSha;
        String rawRef = RawReceiptV1.deriveRawRef(config.mode(), ProviderType.FREE_PUBLIC,
                item.itemId(), receivedAt, runId);
        String anchor = "o_curinstrument[PRODUCTID=ad_f,DELIVERYMONTH="
                + settlement.deliveryMonth() + "].SETTLEMENTPRICE";
        String reference = "上期所日交易快讯;交易代码=AD;主力合约=AD"
                + settlement.deliveryMonth() + ";选择规则=成交量最大;成交量=" + settlement.volume();
        return new RawReceiptV1(
                SchemaV1.VERSION, rawRef, acquisitionId, runId,
                config.mode(), ProviderType.FREE_PUBLIC, AccessMethod.FREE_PUBLIC_WEB,
                config.configVersion(), SOURCE_NAME, response.responseUri().toString(), reference,
                item.itemId(), dateToken, settlement.businessDate().toString(), null, null,
                receivedAt, null, settlement.settlementPrice(), item.unit(), item.currency(), null,
                response.statusCode(), response.contentType(), "base64",
                Base64.getEncoder().encodeToString(payload), payloadSha, anchor, receivedAt,
                DataPaths.acquisitionRef(acquisitionId), null);
    }

    private static URI dailyUri(LocalDate date) {
        return URI.create(DAILY_PREFIX + date.format(BASIC_DATE) + ".dat");
    }

    private static ShfeHttpResponse requireJson(ShfeHttpResponse response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("SHFE public response was not successful: " + response.statusCode());
        }
        String contentType = response.contentType().toLowerCase(Locale.ROOT);
        if (!contentType.startsWith("application/json")) {
            throw new IllegalStateException("SHFE public response is not application/json");
        }
        return response;
    }
}
