package com.supplymind.localimport;

import com.supplymind.foundation.model.SchemaValidationException;
import com.supplymind.provider.ProviderModelChecks;

/**
 * D3-T05 one parsed LocalImport row. The column set mirrors the frozen D3-T04 Manual
 * submission fields; sourceReference is required and sourceUrl may be null (docs/01 frozen
 * Manual/LocalImport source rules).
 */
public record LocalImportRow(
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
    public LocalImportRow {
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
}
