package com.supplymind.manual;

import com.supplymind.foundation.model.SchemaValidationException;
import com.supplymind.provider.ProviderModelChecks;

/**
 * D3-T04 controlled Manual material submission. The client supplies only business/source
 * facts; operatorRef, timestamps and accessMethod are server-side and never part of this DTO,
 * so a client cannot specify or impersonate the operator.
 */
public record ManualMaterialSubmission(
        String schemaVersion,
        String itemId,
        String businessDate,
        String value,
        String unit,
        String currency,
        String actualSourceName,
        String sourceReference,
        String sourceUrl
) {
    public ManualMaterialSubmission {
        ProviderModelChecks.schemaVersion(schemaVersion);
        ProviderModelChecks.identifier(itemId, "itemId");
        ProviderModelChecks.nonBlank(businessDate, "businessDate");
        if (!businessDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new SchemaValidationException("businessDate must be a strict ISO date YYYY-MM-DD");
        }
        ProviderModelChecks.nonBlank(value, "value");
        ProviderModelChecks.nonBlank(unit, "unit");
        ProviderModelChecks.nonBlank(currency, "currency");
        ProviderModelChecks.nonBlank(actualSourceName, "actualSourceName");
        ProviderModelChecks.nonBlank(sourceReference, "sourceReference");
        if (sourceUrl != null) {
            ProviderModelChecks.httpUrl(sourceUrl, "sourceUrl");
        }
    }

    public static ManualMaterialSubmission of(
            String itemId,
            String businessDate,
            String value,
            String unit,
            String currency,
            String actualSourceName,
            String sourceReference,
            String sourceUrl
    ) {
        return new ManualMaterialSubmission(
                "1.0", itemId, businessDate, value, unit, currency,
                actualSourceName, sourceReference, sourceUrl);
    }
}
