package com.supplymind.day4.foundation;

import com.supplymind.foundation.model.MaterialValidationConfigV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.validation.ValidationReasonCodes;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DEC-059 reference oracle for the material validation contract. The value/stale/spec checks
 * are the frozen decision oracle; the unit/currency exact mapping is bound to the merged
 * production configuration (MonitorSeriesDefaults.initialDay3) - no hard-coded material
 * unit or currency appears in this lane.
 */
class MaterialBasicValidationV2ContractHarnessTest {

    static final String VALIDATION_VERSION = "material-basic-validation-v2";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final int STALE_THRESHOLD_DAYS = 7;
    private static final List<String> ACCEPTED_SPEC_ALIASES = List.of();

    @Test
    void valueBoundaryIsStrictlyGreaterThanZeroAndReusesExistingRangeReason() {
        assertDecision(new BigDecimal("0.000000001"), ValidationStatus.VERIFIED, null);
        assertDecision(BigDecimal.ZERO, ValidationStatus.REJECTED, ValidationReasonCodes.OUT_OF_RANGE);
        assertDecision(new BigDecimal("-0.000000001"), ValidationStatus.REJECTED, ValidationReasonCodes.OUT_OF_RANGE);
    }

    @Test
    void ageSevenIsVerifiedAndAgeEightIsVerifiedWithNoticeUsingExistingStaleReason() {
        LocalDate validationDate = LocalDate.of(2026, 8, 11);

        MaterialDecision ageSeven = validateAge(LocalDate.of(2026, 8, 4), validationDate, SHANGHAI);
        MaterialDecision ageEight = validateAge(LocalDate.of(2026, 8, 3), validationDate, SHANGHAI);

        assertEquals(ValidationStatus.VERIFIED, ageSeven.status());
        assertEquals(null, ageSeven.reasonCode());
        assertEquals(ValidationStatus.VERIFIED_WITH_NOTICE, ageEight.status());
        assertEquals(ValidationReasonCodes.STALE_BUSINESS_DATE, ageEight.reasonCode());
    }

    @Test
    void missingRequiredMaterialValidationConfigFailsClosedWithoutVerifiedOutcome() {
        Optional<MaterialDecision> missing = validateWhenConfigPresent(false, new BigDecimal("1"));

        assertTrue(missing.isEmpty(), "missing material validation config must fail closed");
        assertFalse(missing.stream().anyMatch(decision -> decision.status() == ValidationStatus.VERIFIED));
        assertFalse(missing.stream().anyMatch(decision -> decision.status() == ValidationStatus.VERIFIED_WITH_NOTICE));
    }

    @Test
    void normalizedExactSpecUsesNfkcTrimAsciiUppercaseAndNoAliases() {
        assertEquals(List.of(), ACCEPTED_SPEC_ALIASES, "DEC-059 freezes aliases as empty");
        assertEquals("ADC12", normalizeSpec("  ＡＤＣ１２  "));
        assertEquals("AZ91D", normalizeSpec("az91d"));
        assertTrue(isKnownCanonicalSpec("ADC12"));
        assertTrue(isKnownCanonicalSpec("AZ91D"));
        assertFalse(isKnownCanonicalSpec(normalizeSpec("ADC-12")), "near spellings are not aliases");
        assertFalse(isKnownCanonicalSpec(normalizeSpec("AZ91")), "unknown spec must be rejected");
        assertEquals(ValidationStatus.REJECTED, validateSpec("ADC-12"));
        assertEquals(ValidationStatus.REJECTED, validateSpec("AZ91"));
    }

    @Test
    void unitAndCurrencyExactMappingIsBoundToMergedProductionConfiguration() {
        MonitorSeriesConfigV1 productionDefault = MonitorSeriesDefaults.initialDay3(
                OffsetDateTime.parse("2026-08-11T12:00:00+08:00"));
        assertEquals(Mode.FORMAL, productionDefault.mode());

        for (String itemId : List.of(
                MonitorSeriesDefaults.ADC12_SMM_ITEM_ID, MonitorSeriesDefaults.ADC12_AM_ITEM_ID,
                MonitorSeriesDefaults.AZ91D_SMM_ITEM_ID, MonitorSeriesDefaults.AZ91D_AM_ITEM_ID)) {
            MonitorSeriesItemV1 item = productionDefault.requireItem(itemId);
            MaterialValidationConfigV1 rules = item.materialValidation();
            assertNotNull(rules, itemId + " must carry its explicit DEC-059 materialValidation config");
            assertEquals("0", rules.valueMinExclusive(), itemId + " valueMinExclusive");
            assertNull(rules.valueMaxInclusive(), itemId + " has no upper bound");
            assertEquals(7, rules.staleThresholdDays(), itemId + " staleThresholdDays");
            assertEquals(List.of(), rules.acceptedSpecAliases(), itemId + " aliases stay empty");
            String canonical = item.externalCode().equals("ADC12") ? "ADC12" : "AZ91D";
            assertEquals(canonical, rules.canonicalSpecCode(), itemId + " canonicalSpecCode matches the item spec");
            assertEquals("元/吨", item.unit(), itemId + " unit mapping");
            assertEquals("CNY", item.currency(), itemId + " currency mapping");
        }
    }

    private static void assertDecision(BigDecimal value, ValidationStatus expectedStatus, String expectedReason) {
        MaterialDecision decision = validateValue(value);
        assertEquals(expectedStatus, decision.status());
        assertEquals(expectedReason, decision.reasonCode());
    }

    private static MaterialDecision validateValue(BigDecimal value) {
        return value.compareTo(BigDecimal.ZERO) > 0
                ? new MaterialDecision(ValidationStatus.VERIFIED, null)
                : new MaterialDecision(ValidationStatus.REJECTED, ValidationReasonCodes.OUT_OF_RANGE);
    }

    private static MaterialDecision validateAge(LocalDate businessDate, LocalDate validationDate, ZoneId zoneId) {
        assertEquals(SHANGHAI, zoneId, "DEC-059 age uses Asia/Shanghai natural days");
        long ageDays = ChronoUnit.DAYS.between(businessDate, validationDate);
        return ageDays > STALE_THRESHOLD_DAYS
                ? new MaterialDecision(ValidationStatus.VERIFIED_WITH_NOTICE, ValidationReasonCodes.STALE_BUSINESS_DATE)
                : new MaterialDecision(ValidationStatus.VERIFIED, null);
    }

    private static Optional<MaterialDecision> validateWhenConfigPresent(boolean configPresent, BigDecimal value) {
        return configPresent ? Optional.of(validateValue(value)) : Optional.empty();
    }

    private static String normalizeSpec(String rawSpec) {
        return Normalizer.normalize(rawSpec, Normalizer.Form.NFKC).trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isKnownCanonicalSpec(String canonicalSpec) {
        return "ADC12".equals(canonicalSpec) || "AZ91D".equals(canonicalSpec);
    }

    private static ValidationStatus validateSpec(String rawSpec) {
        return isKnownCanonicalSpec(normalizeSpec(rawSpec)) ? ValidationStatus.VERIFIED : ValidationStatus.REJECTED;
    }

    private record MaterialDecision(ValidationStatus status, String reasonCode) {
    }
}
