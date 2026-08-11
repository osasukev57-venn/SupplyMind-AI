package com.supplymind.provider;

import java.util.Set;

/**
 * D3-T01 unified DataProvider port. Callers work against this interface only: they never see
 * concrete source-site implementations, vendor DTOs or URLs. A provider exposes its identity
 * and capability via {@link ProviderSourceProfile}, its monitored targets via
 * {@link #supportedItemIds()}, and returns the unified RawRecord
 * ({@link ProviderCollectOutcome}) from {@link #collect(ProviderCollectRequest)}.
 * Providers must not write aggregates directly and must not invoke LLMs.
 */
public interface DataProvider {

    /** Stable, registry-unique provider identity, e.g. "pboc-official-web". */
    ProviderSourceProfile profile();

    /** The monitored targets this provider can cover; unsupported targets are rejected explicitly. */
    Set<String> supportedItemIds();

    /**
     * Collect the requested targets and return the standardized RawRecord. Unsupported targets
     * are rejected explicitly in the outcome (never silently skipped or replaced by another
     * source). Provider-specific failures fail closed and must not fabricate data.
     */
    ProviderCollectOutcome collect(ProviderCollectRequest request);
}
