package com.supplymind.survey;

import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.model.SchemaValidationException;
import com.supplymind.provider.DataProvider;
import com.supplymind.provider.DataProviderRegistry;
import com.supplymind.provider.ProviderCollectOutcome;
import com.supplymind.provider.ProviderCollectRequest;
import com.supplymind.provider.ProviderSourceProfile;
import com.supplymind.routing.ApiAuthorizationProbe;
import com.supplymind.routing.CandidateUnavailability;
import com.supplymind.routing.DataKind;
import com.supplymind.routing.MaterialRouteConfigV1;
import com.supplymind.routing.MaterialRouteDecision;
import com.supplymind.routing.MaterialRouteResolver;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3-T03 contract tests for the NO_APPROVED_SOURCE path (frozen DoD path B): the survey model
 * records the real public-access investigation facts, the conclusion stays consistent, no
 * FreePublic material provider is fabricated, and the three-tier route degrades explicitly
 * from an unavailable FREE_PUBLIC tier to MANUAL.
 */
class FreePublicSourceSurveyTest {

    private static final OffsetDateTime INVESTIGATED_AT = OffsetDateTime.parse("2026-08-10T18:50:00+08:00");
    private static final String ADC12 = "MAT.ADC12.SMM";
    private static final String AZ91D = "MAT.AZ91D.AM";

    @Test
    void surveyRecordsRealPublicAccessFactsAndNotApprovedVerdicts() {
        FreePublicSourceSurvey smm = FreePublicSourceSurvey.of(
                "smm-cn", "上海有色网（SMM）", "https://www.smm.cn/", INVESTIGATED_AT,
                200, null, 827611, true, false, false, false,
                SourceVerdict.NOT_APPROVED,
                "MEMBER_ONLY: 公开首页可见数据导航与部分报价线索，但结构化价格数据需会员登录；"
                        + "公开页无可按牌号/单位/交货条件/业务日期核对的结构化 ADC12/AZ91D 报价");
        FreePublicSourceSurvey asianMetal = FreePublicSourceSurvey.of(
                "asian-metal-cn", "亚洲金属网（Asian Metal）", "https://www.asianmetal.com.cn/", INVESTIGATED_AT,
                null, "SSL_HANDSHAKE_TERMINATED", null, false, false, false, false,
                SourceVerdict.NOT_APPROVED,
                "NO_PUBLIC_INTERFACE: 正常公开 HTTPS 访问被服务端终止握手（Remote host terminated the handshake），"
                        + "无法取得公开价格接口事实");
        FreePublicSourceSurvey ccmn = FreePublicSourceSurvey.of(
                "ccmn-cn", "长江有色金属网（CCMN）", "https://www.ccmn.cn/", INVESTIGATED_AT,
                200, null, 175755, true, false, false, false,
                SourceVerdict.NOT_APPROVED,
                "MEMBER_ONLY: 首页公开可见铝/镁栏目，但 ADC12/AZ91D 具体报价需会员登录，公开页无结构化报价语义可核对");
        FreePublicSourceSurvey ppi = FreePublicSourceSurvey.of(
                "100ppi-com", "生意社（100ppi）", "https://www.100ppi.com/", INVESTIGATED_AT,
                200, null, 660, false, false, false, false,
                SourceVerdict.NOT_APPROVED,
                "NO_PUBLIC_INTERFACE: 公开首页仅返回约 660 字节引导壳页，无可解析的公开价格数据");

        FreePublicSurveyReport report = FreePublicSurveyReport.noApprovedSource(
                INVESTIGATED_AT, List.of(smm, asianMetal, ccmn, ppi),
                Map.of(
                        ADC12, "MEMBER_ONLY/NO_PUBLIC_INTERFACE: 无获认可免费公开来源",
                        AZ91D, "NO_APPROVED_SOURCE: 无免费公开来源呈现 AZ91D 报价"));

        assertEquals(FreePublicSurveyReport.NO_APPROVED_SOURCE, report.conclusion());
        assertEquals(FreePublicSurveyReport.ROUTE_MANUAL, report.routeConclusion());
        assertEquals(4, report.surveys().size());
        assertTrue(report.surveys().stream().allMatch(s -> s.verdict() == SourceVerdict.NOT_APPROVED));
        assertEquals(2, report.targetReasons().size());
        assertNotNull(report.investigatedAt());
    }

    @Test
    void surveyConclusionFailsClosedOnApprovedInconsistency() {
        FreePublicSourceSurvey approved = FreePublicSourceSurvey.of(
                "approved-source", "已认可测试源", "https://example.test/source", INVESTIGATED_AT,
                200, null, 1000, false, true, true, true,
                SourceVerdict.APPROVED, "APPROVED");
        assertThrows(SchemaValidationException.class,
                () -> FreePublicSurveyReport.noApprovedSource(INVESTIGATED_AT, List.of(approved), Map.of()));
        assertThrows(SchemaValidationException.class,
                () -> new FreePublicSurveyReport(
                        "1.0", INVESTIGATED_AT, FreePublicSurveyReport.APPROVED_SOURCE_FOUND,
                        List.of(), Map.of(), FreePublicSurveyReport.ROUTE_MANUAL));
    }

    @Test
    void noApprovedSourceKeepsNoFreePublicProviderInRegistry() {
        DataProviderRegistry registry = new DataProviderRegistry();
        assertTrue(registry.providersForTarget(ADC12).isEmpty(),
                "without an approved source no FreePublic material provider may exist");
        assertTrue(registry.all().isEmpty());
    }

    @Test
    void unavailableFreePublicTierDegradesExplicitlyToManual() {
        DataProviderRegistry registry = new DataProviderRegistry();
        registry.register(new MaterialTestProvider(
                "smm-authorized-api", ProviderType.AUTHORIZED_API, AccessMethod.AUTHORIZED_API,
                "SMM 授权接口（未配置真实凭证）", true, true, Set.of(ADC12, AZ91D)));
        registry.register(new MaterialTestProvider(
                "material-manual", ProviderType.MANUAL, AccessMethod.MANUAL,
                "材料人工录入", true, false, Set.of(ADC12, AZ91D)));
        ApiAuthorizationProbe probe = (providerId, profile) ->
                profile.providerType() == ProviderType.AUTHORIZED_API
                        ? Optional.of(CandidateUnavailability.CREDENTIALS_MISSING)
                        : Optional.empty();
        MaterialRouteResolver resolver = new MaterialRouteResolver();

        MaterialRouteDecision decision = resolver.resolve(
                MaterialRouteConfigV1.of(
                        ADC12, List.of("smm-authorized-api"), List.of(), List.of("material-manual")),
                registry, probe, DataKind.CURRENT,
                OffsetDateTime.parse("2026-08-10T19:00:00+08:00"));

        assertEquals("material-manual", decision.activeProviderId());
        assertEquals(RouteDecision.FALLBACK_MANUAL, decision.routeDecision());
        assertTrue(decision.fallbackReason().contains("smm-authorized-api=credentials_missing"),
                "fallbackReason must record why PRIMARY and FREE_PUBLIC were skipped: " + decision.fallbackReason());
        assertTrue(decision.candidates().stream().noneMatch(c -> c.tier()
                        == com.supplymind.routing.RouteTier.FREE_PUBLIC),
                "an empty FREE_PUBLIC tier must contribute no fabricated candidate");
        assertFalse(decision.candidates().stream()
                        .anyMatch(c -> c.providerId().equals("material-manual")
                                && !c.unavailability().isAvailable()));
    }

    @Test
    void configReferencingAbsentFreePublicProviderFailsClosed() {
        DataProviderRegistry registry = new DataProviderRegistry();
        registry.register(new MaterialTestProvider(
                "smm-authorized-api", ProviderType.AUTHORIZED_API, AccessMethod.AUTHORIZED_API,
                "SMM 授权接口（未配置真实凭证）", true, true, Set.of(ADC12, AZ91D)));
        MaterialRouteResolver resolver = new MaterialRouteResolver();
        MaterialRouteConfigV1 config = MaterialRouteConfigV1.of(
                ADC12, List.of("smm-authorized-api"), List.of("no-such-free-public"), List.of());
        assertThrows(com.supplymind.routing.ProviderRouteException.class,
                () -> resolver.resolve(config, registry,
                        (id, profile) -> Optional.empty(), DataKind.CURRENT,
                        OffsetDateTime.parse("2026-08-10T19:00:00+08:00")),
                "a route config referencing a provider that was never implemented must fail closed");
    }

    @Test
    void syntheticNeverCarriesTheFormalFreePublicFallback() {
        DataProviderRegistry registry = new DataProviderRegistry();
        registry.register(new MaterialTestProvider(
                "smm-authorized-api", ProviderType.AUTHORIZED_API, AccessMethod.AUTHORIZED_API,
                "SMM 授权接口（未配置真实凭证）", true, true, Set.of(ADC12, AZ91D)));
        registry.register(new MaterialTestProvider(
                "material-synthetic", ProviderType.SYNTHETIC_DEMO, AccessMethod.SYNTHETIC_DEMO,
                "演示合成源", true, false, Set.of(ADC12, AZ91D)));
        MaterialRouteResolver resolver = new MaterialRouteResolver();
        MaterialRouteDecision decision = resolver.resolve(
                MaterialRouteConfigV1.of(
                        ADC12, List.of("smm-authorized-api"), List.of("material-synthetic"), List.of()),
                registry, (id, profile) -> Optional.of(CandidateUnavailability.CREDENTIALS_MISSING),
                DataKind.CURRENT, OffsetDateTime.parse("2026-08-10T19:00:00+08:00"));

        assertNull(decision.activeProviderId());
        assertTrue(decision.candidates().stream()
                        .anyMatch(c -> c.providerId().equals("material-synthetic")
                                && c.unavailability() == CandidateUnavailability.SYNTHETIC_NOT_FORMAL));
    }

    private record MaterialTestProvider(
            String providerId,
            ProviderType providerType,
            AccessMethod accessMethod,
            String actualSourceName,
            boolean supportsCurrentData,
            boolean supportsHistoryData,
            Set<String> supportedItemIds
    ) implements DataProvider {
        @Override
        public ProviderSourceProfile profile() {
            return ProviderSourceProfile.of(providerId, providerType, accessMethod,
                    actualSourceName, "https://test.example/source", supportsCurrentData, supportsHistoryData);
        }

        @Override
        public Set<String> supportedItemIds() {
            return supportedItemIds;
        }

        @Override
        public ProviderCollectOutcome collect(ProviderCollectRequest request) {
            java.util.LinkedHashMap<String, String> rejected = new java.util.LinkedHashMap<>();
            for (String itemId : request.itemIds()) {
                if (!supportedItemIds.contains(itemId)) {
                    rejected.put(itemId, "UNSUPPORTED_TARGET");
                }
            }
            return ProviderCollectOutcome.rejectedOnly(providerId, rejected);
        }
    }
}
