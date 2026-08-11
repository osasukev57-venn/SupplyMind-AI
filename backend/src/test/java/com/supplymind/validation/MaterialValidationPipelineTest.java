package com.supplymind.validation;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.CandidateV1;
import com.supplymind.foundation.model.MaterialValidationConfigV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.model.SchemaValidationException;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.localimport.LocalImportCsvParser;
import com.supplymind.localimport.LocalImportFileStore;
import com.supplymind.localimport.LocalImportResult;
import com.supplymind.localimport.LocalImportService;
import com.supplymind.manual.ManualIntakeOutcome;
import com.supplymind.manual.ManualMaterialIntakeService;
import com.supplymind.manual.ManualMaterialNormalizer;
import com.supplymind.manual.ManualMaterialSubmission;
import com.supplymind.manual.OperatorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D4-T01 material validation pipeline acceptance (DEC-057 §6/§7 + DEC-059): Manual ADC12/AZ91D
 * and LocalImport CSV/XLSX material rows go through the same gate as every provider, advancing
 * PARSED+PENDING -> VALIDATED with deterministic VERIFIED / VERIFIED_WITH_NOTICE / REJECTED /
 * CONFLICT verdicts under material-basic-validation-v2 (value > valueMinExclusive,
 * staleThresholdDays=7 notice, normalized-exact spec). No PUBLISHED is produced (publication
 * belongs to D4-T02), no bypass exists, old valid versions are never overwritten, Synthetic
 * DEMO never leaks into the formal chain, and the declared source name can never change
 * provider identity. material-basic-validation-v1 is preserved as history only.
 */
class MaterialValidationPipelineTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-10T02:00:00+08:00");
    private static final String ADC12_SMM = "MAT.ADC12.SMM";
    private static final String AZ91D_AM = "MAT.AZ91D.AM";
    private static final String IMP_ADC12 = "IMP.ADC12.001";
    private static final String IMP_AZ91D = "IMP.AZ91D.001";
    private static final String FREE_ADC12 = "FREE.ADC12.001";
    private static final String DEMO_ADC12 = "DEMO.ADC12.001";
    private static final String HEADER = "schemaVersion,itemId,businessDate,value,unit,currency,"
            + "actualSourceName,sourceReference,sourceUrl";

    @TempDir
    Path temporaryDirectory;

    @Test
    void normalAdc12ManualBecomesValidatedVerified() throws IOException {
        Harness harness = harness();
        ManualIntakeOutcome intake = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19850.50", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        assertEquals(ProcessingStage.PARSED, intake.processingStage());
        assertEquals(ValidationStatus.PENDING, intake.validationStatus());

        ValidationOutcome outcome = harness.validation().process(intake.runId());
        assertValidated(outcome, ValidationStatus.VERIFIED, null, "19850.50", "2026-08-10");

        ValidationOutcome replay = harness.validation().process(intake.runId());
        assertEquals(3, replay.recordVersion(), "reprocessing a VALIDATED run is a no-op");
        assertEquals(ValidationStatus.VERIFIED, replay.validationStatus());
    }

    @Test
    void normalAz91dManualBecomesValidatedVerified() throws IOException {
        Harness harness = harness();
        ManualIntakeOutcome intake = harness.manual().submit(ManualMaterialSubmission.of(
                AZ91D_AM, "2026-08-10", "24500", "元/吨", "CNY",
                "西南某厂报价单（测试）", "报价单号B-20260810", "https://example.test/ref"));
        ValidationOutcome outcome = harness.validation().process(intake.runId());
        assertValidated(outcome, ValidationStatus.VERIFIED, null, "24500", "2026-08-10");
    }

    @Test
    void missingRequiredFieldsStayIntakeRejectedAndNeverValidate() throws IOException {
        Harness harness = harness();
        assertThrows(SchemaValidationException.class, () -> ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19850.50", "元/吨", "CNY",
                "华东某厂报价单（测试）", " ", null),
                "a blank sourceReference is rejected at the intake contract");
        assertThrows(SchemaValidationException.class, () -> ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19850.50", "元/吨", "CNY",
                " ", "报价单号A-20260810", null),
                "a blank declared actualSourceName is rejected at the intake contract");
        assertThrows(SchemaValidationException.class, () -> ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", " ", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null),
                "a blank value is rejected at the intake contract");
    }

    @Test
    void nonDecimalValueStaysIntakeRejected() throws IOException {
        Harness harness = harness();
        ManualIntakeOutcome outcome = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "abc", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        assertEquals(ProcessingStage.RECEIVED, outcome.processingStage());
        assertEquals(ValidationStatus.REJECTED, outcome.validationStatus());
        assertEquals("VALUE_NOT_DECIMAL", outcome.reasonCode());
    }

    @Test
    void unitMismatchIsRejectedAtValidation() throws IOException {
        Harness harness = harness();
        ManualIntakeOutcome intake = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19850.50", "元/公斤", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        ValidationOutcome outcome = harness.validation().process(intake.runId());
        assertValidated(outcome, ValidationStatus.REJECTED, ValidationReasonCodes.UNIT_MISMATCH,
                "19850.50", "2026-08-10");
    }

    @Test
    void currencyMismatchIsRejectedAtValidation() throws IOException {
        Harness harness = harness();
        ManualIntakeOutcome intake = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19850.50", "元/吨", "USD",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        ValidationOutcome outcome = harness.validation().process(intake.runId());
        assertValidated(outcome, ValidationStatus.REJECTED, ValidationReasonCodes.CURRENCY_MISMATCH,
                "19850.50", "2026-08-10");
    }

    @Test
    void futureBusinessDateIsRejectedAtValidation() throws IOException {
        Harness harness = harness();
        ManualIntakeOutcome intake = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-11", "19850.50", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        ValidationOutcome outcome = harness.validation().process(intake.runId());
        assertValidated(outcome, ValidationStatus.REJECTED, ValidationReasonCodes.FUTURE_BUSINESS_DATE,
                "19850.50", "2026-08-11");
    }

    @Test
    void sourceFieldInconsistencyIsRejected() throws IOException {
        Harness harness = harness();
        String runId = "manual-inconsistent-001";
        RawReceiptV1 raw = materialRaw(runId, ADC12_SMM, ProviderType.MANUAL, AccessMethod.MANUAL,
                Mode.FORMAL, "2026-08-10", "19850.50", "元/吨", "CNY",
                "原始raw声明来源", "报价单号A-20260810", null, "op-d4t01",
                payloadOf("payload声明来源"));
        harness.rawStore().store(raw);
        harness.timelineStore().createInitial(raw.runId(), raw.rawRef(), raw.receivedAt());
        ValidationOutcome outcome = harness.validation().process(runId);
        assertValidated(outcome, ValidationStatus.REJECTED, ValidationReasonCodes.SOURCE_MISMATCH,
                "19850.50", "2026-08-10");
    }

    @Test
    void manualDeclaredOfficialSourceNameKeepsManualIdentity() throws IOException {
        Harness harness = harness();
        ManualIntakeOutcome intake = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19850.50", "元/吨", "CNY",
                "SMM官方页面报价（人工录入声明）", "用户自述来源-测试", null));
        ValidationOutcome outcome = harness.validation().process(intake.runId());
        assertValidated(outcome, ValidationStatus.VERIFIED, null, "19850.50", "2026-08-10");
        assertEquals(ProviderType.MANUAL, outcome.candidate().providerType(),
                "a declared SMM name must never turn the provider identity into OfficialWeb");
        assertEquals(AccessMethod.MANUAL, outcome.candidate().accessMethod());
        assertEquals("SMM官方页面报价（人工录入声明）", outcome.candidate().actualSourceName());
    }

    @Test
    void freePublicDeclaredOfficialLabelStaysFreePublicAndNeverAutoTrusts() throws IOException {
        Harness harness = harness();
        String runId = "free-adc12-001";
        RawReceiptV1 raw = httpMaterialRaw(runId, FREE_ADC12, "SMM官网页面（声明）",
                "2026-08-10", "19900", "元/吨", "CNY", payloadOf("SMM官网页面（声明）"));
        ingestHttp(harness, raw);

        ValidationOutcome outcome = harness.validation().process(runId);
        assertValidated(outcome, ValidationStatus.VERIFIED, null, "19900", "2026-08-10");
        assertEquals(ProviderType.FREE_PUBLIC, outcome.candidate().providerType(),
                "a FreePublic record labeled SMM stays FREE_PUBLIC, never OFFICIAL_WEB");
        assertEquals(AccessMethod.FREE_PUBLIC_WEB, outcome.candidate().accessMethod());

        String badUnitRun = "free-adc12-002";
        RawReceiptV1 badUnit = httpMaterialRaw(badUnitRun, FREE_ADC12, "SMM官网页面（声明）",
                "2026-08-10", "19900", "元/公斤", "CNY", payloadOf("SMM官网页面（声明）"));
        ingestHttp(harness, badUnit);
        ValidationOutcome bad = harness.validation().process(badUnitRun);
        assertValidated(bad, ValidationStatus.REJECTED, ValidationReasonCodes.UNIT_MISMATCH,
                "19900", "2026-08-10");
    }

    @Test
    void revisionWithDifferentDeclaredSourceValidatesIndependentlyAndOldVersionKept() throws IOException {
        Harness harness = harness();
        ManualIntakeOutcome first = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19850.50", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        ValidationOutcome firstValidated = harness.validation().process(first.runId());
        assertValidated(firstValidated, ValidationStatus.VERIFIED, null, "19850.50", "2026-08-10");

        ManualIntakeOutcome revision = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "20000.00", "元/吨", "CNY",
                "华东另一厂报价单（测试）", "报价单号A2-20260810", null));
        assertFalse(first.runId().equals(revision.runId()), "a revision is a new pending version");
        ValidationOutcome revisionValidated = harness.validation().process(revision.runId());
        assertValidated(revisionValidated, ValidationStatus.VERIFIED, null, "20000.00", "2026-08-10");

        ValidationOutcome invalidRevision = harness.validation().process(
                harness.manual().submit(ManualMaterialSubmission.of(
                        ADC12_SMM, "2026-08-10", "20000.00", "元/公斤", "CNY",
                        "华东某厂报价单（测试）", "报价单号A-20260810", null)).runId());
        assertValidated(invalidRevision, ValidationStatus.REJECTED, ValidationReasonCodes.UNIT_MISMATCH,
                "20000.00", "2026-08-10");

        assertEquals(ValidationStatus.VERIFIED, harness.validation().process(first.runId()).validationStatus(),
                "the old valid version stays VALIDATED+VERIFIED after revisions");
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(first.rawRef())),
                "the old raw stays immutable");
    }

    @Test
    void conflictingValueOnSameKeyAndSourceIsConflict() throws IOException {
        Harness harness = harness();
        ManualIntakeOutcome first = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19850.50", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        ValidationOutcome firstValidated = harness.validation().process(first.runId());
        assertValidated(firstValidated, ValidationStatus.VERIFIED, null, "19850.50", "2026-08-10");

        ManualIntakeOutcome conflicting = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19900.00", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        ValidationOutcome conflict = harness.validation().process(conflicting.runId());
        assertValidated(conflict, ValidationStatus.CONFLICT, ValidationReasonCodes.VALUE_CONFLICT,
                "19900.00", "2026-08-10");
        assertEquals(ValidationStatus.VERIFIED, harness.validation().process(first.runId()).validationStatus(),
                "a conflict never overwrites or degrades the prior valid observation");
    }

    @Test
    void duplicateObservationOnSameKeyAndSourceIsNotice() throws IOException {
        Harness harness = harness();
        ManualIntakeOutcome first = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19850.50", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        harness.validation().process(first.runId());
        ManualIntakeOutcome duplicate = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19850.50", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", "https://example.test/ref"));
        ValidationOutcome outcome = harness.validation().process(duplicate.runId());
        assertValidated(outcome, ValidationStatus.VERIFIED_WITH_NOTICE,
                ValidationReasonCodes.DUPLICATE_OBSERVATION, "19850.50", "2026-08-10");
    }

    @Test
    void localImportCsvAndXlsxValidateThroughTheSameGate() throws IOException {
        Harness harness = harness();
        String csv = HEADER + "\n"
                + "1.0," + IMP_ADC12 + ",2026-08-10,123.456789012345678,元/吨,CNY,华东某厂CSV报价单,CSV-REF-A,\n"
                + "1.0," + IMP_AZ91D + ",2026-08-10,24500,元/吨,CNY,西南某厂CSV报价单,CSV-REF-B,\n";
        LocalImportResult csvResult = harness.importService().importFile(csv.getBytes(StandardCharsets.UTF_8));
        assertFalse(csvResult.fileFailed());
        assertEquals(2, csvResult.accepted().size());
        for (LocalImportResult.RowOutcome outcome : csvResult.accepted()) {
            assertEquals(ProcessingStage.RECEIVED, outcome.processingStage());
            assertEquals(ValidationStatus.PENDING, outcome.validationStatus());
            ValidationOutcome validated = harness.validation().process(outcome.runId());
            assertValidated(validated, ValidationStatus.VERIFIED, null, null, "2026-08-10");
        }

        byte[] xlsx = buildXlsx(new String[][]{
                LocalImportCsvParser.TEMPLATE_HEADER.toArray(new String[0]),
                {"1.0", IMP_ADC12, "2026-08-10", "123.456789012345678", "元/吨", "CNY", "华东某厂XLSX报价单", "XLSX-REF-A", ""},
                {"1.0", IMP_AZ91D, "2026-08-10", "24500", "元/吨", "CNY", "西南某厂XLSX报价单", "XLSX-REF-B", ""}
        });
        LocalImportResult xlsxResult = harness.importService().importFile(xlsx);
        assertFalse(xlsxResult.fileFailed());
        assertEquals(2, xlsxResult.accepted().size());
        for (LocalImportResult.RowOutcome outcome : xlsxResult.accepted()) {
            ValidationOutcome validated = harness.validation().process(outcome.runId());
            assertValidated(validated, ValidationStatus.VERIFIED, null, null, "2026-08-10");
        }
    }

    @Test
    void localImportStandardizationFailureStaysReceivedRejected() throws IOException {
        Harness harness = harness();
        String csv = HEADER + "\n"
                + "1.0," + IMP_ADC12 + ",2026-08-10,not-a-number,元/吨,CNY,华东某厂CSV报价单,CSV-REF-A,\n";
        LocalImportResult result = harness.importService().importFile(csv.getBytes(StandardCharsets.UTF_8));
        assertFalse(result.fileFailed());
        assertTrue(result.accepted().isEmpty(), "a non-decimal value is rejected at the mechanical row gate");
        assertFalse(result.rowErrors().isEmpty());
    }

    @Test
    void syntheticDemoModeIsNeverValidatedIntoFormalChain() throws IOException {
        Harness harness = harness();
        String runId = "synthetic-demo-001";
        RawReceiptV1 raw = materialRaw(runId, DEMO_ADC12, ProviderType.SYNTHETIC_DEMO,
                AccessMethod.SYNTHETIC_DEMO, Mode.DEMO, "2026-08-10", "19850.50", "元/吨", "CNY",
                "SyntheticDemo（演示）", "fixture-demo-001", null, null,
                payloadOf("SyntheticDemo（演示）"));
        assertThrows(com.supplymind.foundation.storage.StorageException.class,
                () -> harness.rawStore().store(raw),
                "a DEMO-mode synthetic raw cannot be stored against the FORMAL config snapshot");
        writeRawDirectly(harness, raw);
        harness.timelineStore().createInitial(runId, raw.rawRef(), raw.receivedAt());
        ValidationOutcome outcome = harness.validation().process(runId);
        assertValidated(outcome, ValidationStatus.REJECTED, ValidationReasonCodes.SOURCE_MISMATCH,
                "19850.50", "2026-08-10");
        assertFalse(outcome.validationStatus() == ValidationStatus.VERIFIED
                        || outcome.validationStatus() == ValidationStatus.VERIFIED_WITH_NOTICE,
                "a DEMO-mode synthetic run must never become VERIFIED");
    }

    @Test
    void verifiedMaterialIsNeverPublishedByValidation() throws IOException {
        Harness harness = harness();
        ManualIntakeOutcome intake = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19850.50", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        ValidationOutcome outcome = harness.validation().process(intake.runId());
        assertValidated(outcome, ValidationStatus.VERIFIED, null, "19850.50", "2026-08-10");
        assertEquals(ProcessingStage.VALIDATED, outcome.processingStage());
        assertNotNull(outcome.validatedAt());
        com.supplymind.foundation.model.LifecycleTimelineV1 timeline = harness.timelineStore().read(intake.runId());
        assertNull(timeline.current().publishedAt(), "validation must never produce PUBLISHED (D4-T02 owns publication)");
        assertNull(timeline.current().publishRef());
    }

    @Test
    void valueBoundaryZeroAndNegativeAreRejectedOutOfRange() throws IOException {
        Harness harness = harness();
        ValidationOutcome zero = harness.validation().process(harness.manual().submit(
                ManualMaterialSubmission.of(ADC12_SMM, "2026-08-10", "0", "元/吨", "CNY",
                        "华东某厂报价单（测试）", "报价单号A-20260810", null)).runId());
        assertValidated(zero, ValidationStatus.REJECTED, ValidationReasonCodes.OUT_OF_RANGE,
                "0", "2026-08-10");
        ValidationOutcome negative = harness.validation().process(harness.manual().submit(
                ManualMaterialSubmission.of(ADC12_SMM, "2026-08-10", "-1.5", "元/吨", "CNY",
                        "华东某厂报价单（测试）", "报价单号A-20260810", null)).runId());
        assertValidated(negative, ValidationStatus.REJECTED, ValidationReasonCodes.OUT_OF_RANGE,
                "-1.5", "2026-08-10");
    }

    @Test
    void staleBoundarySevenDaysIsNotStaleAndEightDaysIsNotice() throws IOException {
        Harness harness = harness();
        ValidationOutcome ageSeven = harness.validation().process(harness.manual().submit(
                ManualMaterialSubmission.of(ADC12_SMM, "2026-08-03", "19850.50", "元/吨", "CNY",
                        "华东某厂报价单（测试）", "报价单号A-20260810", null)).runId());
        assertValidated(ageSeven, ValidationStatus.VERIFIED, null, "19850.50", "2026-08-03");
        ValidationOutcome ageEight = harness.validation().process(harness.manual().submit(
                ManualMaterialSubmission.of(ADC12_SMM, "2026-08-02", "19850.50", "元/吨", "CNY",
                        "华东某厂报价单（测试）", "报价单号A-20260810", null)).runId());
        assertValidated(ageEight, ValidationStatus.VERIFIED_WITH_NOTICE,
                ValidationReasonCodes.STALE_BUSINESS_DATE, "19850.50", "2026-08-02");
    }

    @Test
    void missingMaterialValidationConfigFailsConstructionFailClosed() {
        assertThrows(SchemaValidationException.class, () -> new MonitorSeriesItemV1(
                ADC12_SMM, ADC12_SMM, true, "SMM", ProviderType.MANUAL, AccessMethod.MANUAL,
                "人工录入（Manual）", RouteDecision.FALLBACK_MANUAL, "MANUAL_FALLBACK", NOW, null,
                "ADC12", "material-field-key", "material",
                "arithmetic-mean-v1", 2, 2, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "CNY", "元/吨", null),
                "a material item without its explicit DEC-059 materialValidation config must fail closed");
        assertThrows(SchemaValidationException.class, () -> new MonitorSeriesItemV1(
                "FX.USD.CNY.PBOC_MID", "美元/人民币中间价", true, "PBOC", ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML, "中国人民银行官网（授权中国外汇交易中心公布）",
                RouteDecision.PRIMARY, null, NOW, null, "USD", "1美元对人民币", "人民币汇率中间价",
                "arithmetic-mean-v1", 8, 4, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "USD", "CNY/1 USD",
                new MaterialValidationConfigV1("0", null, 7, "USD", List.of())),
                "a non-material item must not carry materialValidation config");
    }

    @Test
    void normalizedSpecMatchingAndUnknownSpecRejected() throws IOException {
        Harness harness = harness();
        ValidationOutcome normalized = harness.validation().process(harness.manual().submit(
                ManualMaterialSubmission.of(ADC12_SMM, "2026-08-10", "19850.50", "元/吨", "CNY",
                        "华东某厂报价单（测试）", "报价单号A-20260810", null)).runId());
        assertValidated(normalized, ValidationStatus.VERIFIED, null, "19850.50", "2026-08-10");

        MonitorSeriesItemV1 unknown = new MonitorSeriesItemV1(
                "MAT.ADC12X.SMM", "MAT.ADC12X.SMM", true, "SMM", ProviderType.MANUAL, AccessMethod.MANUAL,
                "人工录入（Manual）", RouteDecision.FALLBACK_MANUAL, "MANUAL_FALLBACK", NOW, null,
                "ADC12X", "material-field-key", "material",
                "arithmetic-mean-v1", 2, 2, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "CNY", "元/吨",
                new MaterialValidationConfigV1("0", null, 7, "ADC12", List.of()));
        Harness secondHarness = harness();
        secondHarness.configStore().activate(replaceItem(materialConfig(), unknown));
        ManualIntakeOutcome intake = secondHarness.manual().submit(ManualMaterialSubmission.of(
                "MAT.ADC12X.SMM", "2026-08-10", "19850.50", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        ValidationOutcome outcome = secondHarness.validation().process(intake.runId());
        assertValidated(outcome, ValidationStatus.REJECTED, ValidationReasonCodes.SPEC_MISMATCH,
                "19850.50", "2026-08-10");
    }

    @Test
    void aliasesAreNeverImplied() throws IOException {
        Harness harness = harness();
        MonitorSeriesItemV1 hyphenated = new MonitorSeriesItemV1(
                "MAT.ADC-12.SMM", "MAT.ADC-12.SMM", true, "SMM", ProviderType.MANUAL, AccessMethod.MANUAL,
                "人工录入（Manual）", RouteDecision.FALLBACK_MANUAL, "MANUAL_FALLBACK", NOW, null,
                "ADC-12", "material-field-key", "material",
                "arithmetic-mean-v1", 2, 2, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "CNY", "元/吨",
                new MaterialValidationConfigV1("0", null, 7, "ADC12", List.of()));
        Harness secondHarness = harness();
        secondHarness.configStore().activate(replaceItem(materialConfig(), hyphenated));
        ManualIntakeOutcome intake = secondHarness.manual().submit(ManualMaterialSubmission.of(
                "MAT.ADC-12.SMM", "2026-08-10", "19850.50", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        ValidationOutcome outcome = secondHarness.validation().process(intake.runId());
        assertValidated(outcome, ValidationStatus.REJECTED, ValidationReasonCodes.SPEC_MISMATCH,
                "19850.50", "2026-08-10");
    }

    @Test
    void az91dValueAndStaleFollowTheSameRules() throws IOException {
        Harness harness = harness();
        ValidationOutcome zero = harness.validation().process(harness.manual().submit(
                ManualMaterialSubmission.of(AZ91D_AM, "2026-08-10", "0", "元/吨", "CNY",
                        "西南某厂报价单（测试）", "报价单号B-20260810", null)).runId());
        assertValidated(zero, ValidationStatus.REJECTED, ValidationReasonCodes.OUT_OF_RANGE,
                "0", "2026-08-10");
        ValidationOutcome stale = harness.validation().process(harness.manual().submit(
                ManualMaterialSubmission.of(AZ91D_AM, "2026-08-02", "24500", "元/吨", "CNY",
                        "西南某厂报价单（测试）", "报价单号B-20260810", null)).runId());
        assertValidated(stale, ValidationStatus.VERIFIED_WITH_NOTICE,
                ValidationReasonCodes.STALE_BUSINESS_DATE, "24500", "2026-08-02");
    }

    @Test
    void v1ValidationVersionIsPreservedAsHistoryOnly() {
        assertEquals("material-basic-validation-v1", MaterialCandidateValidator.VALIDATION_VERSION,
                "material-basic-validation-v1 must stay frozen as the historical version string");
        assertEquals("material-basic-validation-v2", MaterialCandidateValidatorV2.VALIDATION_VERSION);
    }

    private static MonitorSeriesConfigV1 replaceItem(MonitorSeriesConfigV1 config, MonitorSeriesItemV1 replacement) {
        List<MonitorSeriesItemV1> items = new ArrayList<>();
        boolean replaced = false;
        for (MonitorSeriesItemV1 item : config.items()) {
            if (item.itemId().equals(replacement.itemId())) {
                items.add(replacement);
                replaced = true;
            } else {
                items.add(item);
            }
        }
        if (!replaced) {
            items.add(replacement);
        }
        return new MonitorSeriesConfigV1(config.schemaVersion(), config.configVersion() + 1,
                config.mode(), config.updatedAt().plusMinutes(1), items);
    }

    private static void assertValidated(
            ValidationOutcome outcome,
            ValidationStatus status,
            String reasonCode,
            String value,
            String businessDate
    ) {
        assertEquals(ProcessingStage.VALIDATED, outcome.processingStage());
        assertEquals(status, outcome.validationStatus());
        assertEquals(reasonCode, outcome.reasonCode());
        assertEquals(3, outcome.recordVersion());
        assertEquals(MaterialCandidateValidatorV2.VALIDATION_VERSION, outcome.validationVersion(),
                "materials must use the official material-basic-validation-v2, never pboc-basic-validation-v1");
        assertNotNull(outcome.validatedAt());
        assertNotNull(outcome.candidate());
        if (value != null) {
            assertEquals(value, outcome.candidate().value());
        }
        if (businessDate != null) {
            assertEquals(businessDate, outcome.candidate().businessDate());
        }
        if (status == ValidationStatus.VERIFIED) {
            assertNull(reasonCode);
        }
    }

    private Harness harness() {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("d4-t01 pipeline root"));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configStore = new ConfigActivationStore(root, fileStore, CLOCK);
        configStore.activate(materialConfig());
        RawReceiptStore rawStore = new RawReceiptStore(root, fileStore, CLOCK);
        TimelineStore timelineStore = new TimelineStore(root, fileStore, CLOCK);
        ManualMaterialIntakeService manual = new ManualMaterialIntakeService(
                root, rawStore, timelineStore, new ManualMaterialNormalizer(),
                OperatorContext.configured("op-d4t01"), CLOCK);
        LocalImportService importService = new LocalImportService(
                root, rawStore, new LocalImportFileStore(root, fileStore, CLOCK), timelineStore,
                new LocalImportCsvParser(), CLOCK);
        LifecycleValidationService validation = new LifecycleValidationService(root, timelineStore, CLOCK);
        return new Harness(root, configStore, rawStore, timelineStore, manual, importService, validation);
    }

    private static MonitorSeriesConfigV1 materialConfig() {
        List<MonitorSeriesItemV1> items = new ArrayList<>();
        items.add(item(ADC12_SMM, "SMM", ProviderType.MANUAL, AccessMethod.MANUAL,
                RouteDecision.FALLBACK_MANUAL, "MANUAL_FALLBACK", "ADC12", true));
        items.add(item(AZ91D_AM, "Asian Metal", ProviderType.MANUAL, AccessMethod.MANUAL,
                RouteDecision.FALLBACK_MANUAL, "MANUAL_FALLBACK", "AZ91D", true));
        items.add(item(IMP_ADC12, "SMM/供应商", ProviderType.LOCAL_IMPORT, AccessMethod.LOCAL_IMPORT,
                RouteDecision.DIRECT_LOCAL_IMPORT, null, "ADC12", true));
        items.add(item(IMP_AZ91D, "SMM/供应商", ProviderType.LOCAL_IMPORT, AccessMethod.LOCAL_IMPORT,
                RouteDecision.DIRECT_LOCAL_IMPORT, null, "AZ91D", true));
        items.add(item(FREE_ADC12, "免费公开源", ProviderType.FREE_PUBLIC, AccessMethod.FREE_PUBLIC_WEB,
                RouteDecision.FALLBACK_FREE_PUBLIC, "FREE_PUBLIC_FALLBACK", "ADC12", true));
        items.add(item(DEMO_ADC12, "SyntheticDemo", ProviderType.SYNTHETIC_DEMO, AccessMethod.SYNTHETIC_DEMO,
                RouteDecision.SYNTHETIC_DEMO, null, "ADC12", true));
        return new MonitorSeriesConfigV1("1.0", 1, Mode.FORMAL, NOW, items);
    }

    private static MonitorSeriesItemV1 item(
            String itemId, String sourceIntent, ProviderType providerType, AccessMethod accessMethod,
            RouteDecision routeDecision, String fallbackReason, String externalCode, boolean enabled
    ) {
        return new MonitorSeriesItemV1(
                itemId, itemId, enabled, sourceIntent, providerType, accessMethod,
                "人工录入（Manual）", routeDecision, fallbackReason, NOW, null,
                externalCode, "material-field-key", "material",
                "arithmetic-mean-v1", 2, 2, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "CNY", "元/吨",
                new MaterialValidationConfigV1("0", null, 7, externalCode, List.of()));
    }

    private static RawReceiptV1 materialRaw(
            String runId, String itemId, ProviderType providerType, AccessMethod accessMethod, Mode mode,
            String businessDate, String value, String unit, String currency,
            String actualSourceName, String sourceReference, String sourceUrl, String operatorRef,
            byte[] payload
    ) {
        OffsetDateTime receivedAt = OffsetDateTime.parse("2026-08-10T02:00:00+08:00");
        String rawRef = RawReceiptV1.deriveRawRef(mode, providerType, itemId, receivedAt, runId);
        boolean synthetic = providerType == ProviderType.SYNTHETIC_DEMO;
        return new RawReceiptV1(
                "1.0", rawRef, "manual-acq-" + runId, runId, mode,
                providerType, accessMethod, 1,
                actualSourceName, sourceUrl, sourceReference, itemId,
                businessDate, businessDate, null, null,
                receivedAt,
                synthetic ? null : receivedAt,
                value, unit, currency,
                synthetic ? null : operatorRef, null, "application/json", "base64",
                Base64.getEncoder().encodeToString(payload),
                com.supplymind.foundation.storage.FileDigest.sha256(payload),
                null, receivedAt, null, null);
    }

    private static RawReceiptV1 httpMaterialRaw(
            String runId, String itemId, String actualSourceName,
            String businessDate, String value, String unit, String currency, byte[] payload
    ) {
        OffsetDateTime receivedAt = OffsetDateTime.parse("2026-08-10T02:00:00+08:00");
        String rawRef = RawReceiptV1.deriveRawRef(Mode.FORMAL, ProviderType.FREE_PUBLIC, itemId,
                receivedAt, runId);
        String acquisitionId = "free-acq-" + runId;
        return new RawReceiptV1(
                "1.0", rawRef, acquisitionId, runId, Mode.FORMAL,
                ProviderType.FREE_PUBLIC, AccessMethod.FREE_PUBLIC_WEB, 1,
                actualSourceName, "https://example.test/source", "free-ref-" + runId, itemId,
                businessDate, businessDate, null, null,
                receivedAt,
                null,
                value, unit, currency, null, 200, "text/html", "base64",
                Base64.getEncoder().encodeToString(payload),
                com.supplymind.foundation.storage.FileDigest.sha256(payload),
                null, receivedAt,
                com.supplymind.foundation.storage.DataPaths.acquisitionRef(acquisitionId), null);
    }

    private static void ingestHttp(Harness harness, RawReceiptV1 raw) throws IOException {
        new com.supplymind.foundation.storage.RawAcquisitionStore(
                harness.root(), new AtomicFileStore(harness.root(), new DirtyMarkerCodec()), CLOCK)
                .store(com.supplymind.foundation.model.DomainFixtures.acquisitionFor(raw));
        harness.rawStore().store(raw);
        harness.timelineStore().createInitial(raw.runId(), raw.rawRef(), raw.receivedAt());
    }

    private static void writeRawDirectly(Harness harness, RawReceiptV1 raw) throws IOException {
        byte[] rawBytes = JsonV1Codec.encodeFile(raw);
        com.supplymind.foundation.model.ManifestV1 manifest = com.supplymind.foundation.storage.ManifestFactory.json(
                raw.rawRef(), rawBytes, List.of(raw.runId()), NOW);
        byte[] manifestBytes = JsonV1Codec.encodeFile(manifest);
        new AtomicFileStore(harness.root(), new DirtyMarkerCodec()).commit("raw-direct-" + raw.runId(),
                com.supplymind.foundation.storage.DirtyTransactionType.SINGLE_FILE, NOW,
                List.of(new com.supplymind.foundation.storage.FileTransactionTarget(
                        com.supplymind.foundation.storage.DirtyTargetRole.BUSINESS_FILE,
                        raw.rawRef(), rawBytes, manifestBytes, true)));
    }

    private static byte[] payloadOf(String declaredSourceName) {
        return JsonV1Codec.encodeFile(ManualMaterialSubmission.of(
                "MAT.ADC12.SMM", "2026-08-10", "19850.50", "元/吨", "CNY",
                declaredSourceName, "ref-payload", null));
    }

    private static byte[] buildXlsx(String[][] values) throws IOException {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("import");
            for (int r = 0; r < values.length; r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r);
                for (int c = 0; c < values[r].length; c++) {
                    row.createCell(c).setCellValue(values[r][c]);
                }
            }
            try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                workbook.write(out);
                return out.toByteArray();
            }
        }
    }

    private record Harness(
            DataRoot root,
            ConfigActivationStore configStore,
            RawReceiptStore rawStore,
            TimelineStore timelineStore,
            ManualMaterialIntakeService manual,
            LocalImportService importService,
            LifecycleValidationService validation
    ) {
    }
}
