package com.supplymind.survey;

import com.supplymind.foundation.model.SchemaValidationException;
import com.supplymind.provider.ProviderModelChecks;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * D3-T03 survey conclusion: the set of real per-source investigations and the overall verdict
 * for the material targets. When no source is approved the conclusion is NO_APPROVED_SOURCE
 * and the route conclusion is FREE_PUBLIC_UNAVAILABLE_ROUTE_MANUAL. The model fails closed if
 * any approved survey exists while the conclusion claims no approved source.
 */
public record FreePublicSurveyReport(
        String schemaVersion,
        OffsetDateTime investigatedAt,
        String conclusion,
        List<FreePublicSourceSurvey> surveys,
        Map<String, String> targetReasons,
        String routeConclusion
) {
    public static final String NO_APPROVED_SOURCE = "NO_APPROVED_SOURCE";
    public static final String APPROVED_SOURCE_FOUND = "APPROVED_SOURCE_FOUND";
    public static final String ROUTE_MANUAL = "FREE_PUBLIC_UNAVAILABLE_ROUTE_MANUAL";

    public FreePublicSurveyReport {
        ProviderModelChecks.schemaVersion(schemaVersion);
        Objects.requireNonNull(investigatedAt, "investigatedAt");
        ProviderModelChecks.nonBlank(conclusion, "conclusion");
        surveys = List.copyOf(surveys == null ? List.of() : surveys);
        targetReasons = Map.copyOf(targetReasons == null ? Map.of() : targetReasons);
        ProviderModelChecks.nonBlank(routeConclusion, "routeConclusion");
        boolean anyApproved = surveys.stream().anyMatch(survey -> survey.verdict() == SourceVerdict.APPROVED);
        if (anyApproved && NO_APPROVED_SOURCE.equals(conclusion)) {
            throw new SchemaValidationException(
                    "conclusion cannot be NO_APPROVED_SOURCE while a survey is APPROVED");
        }
        if (!anyApproved && APPROVED_SOURCE_FOUND.equals(conclusion)) {
            throw new SchemaValidationException(
                    "conclusion cannot be APPROVED_SOURCE_FOUND without an approved survey");
        }
    }

    public static FreePublicSurveyReport noApprovedSource(
            OffsetDateTime investigatedAt,
            List<FreePublicSourceSurvey> surveys,
            Map<String, String> targetReasons
    ) {
        return new FreePublicSurveyReport(
                "1.0", investigatedAt, NO_APPROVED_SOURCE, surveys, targetReasons, ROUTE_MANUAL);
    }
}
