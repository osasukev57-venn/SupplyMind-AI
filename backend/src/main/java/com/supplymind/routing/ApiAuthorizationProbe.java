package com.supplymind.routing;

import com.supplymind.provider.ProviderSourceProfile;

import java.util.Optional;

/**
 * D3-T02 runtime capability probe for candidate availability (authorization boundaries).
 * Implementations read configuration/environment references only; they never expose, log or
 * persist secret values. An empty result means the candidate is usable; a non-empty result is
 * the frozen unavailability reason.
 */
public interface ApiAuthorizationProbe {

    Optional<CandidateUnavailability> unavailability(String providerId, ProviderSourceProfile profile);

    /**
     * Safe default: without any configured credentials an AuthorizedApi candidate is
     * NOT_CONFIGURED; all other source types report no unavailability (their tier/capability
     * boundaries are checked by the resolver).
     */
    static ApiAuthorizationProbe defaultProbe() {
        return (providerId, profile) -> profile.providerType()
                == com.supplymind.foundation.model.ProviderType.AUTHORIZED_API
                ? Optional.of(CandidateUnavailability.CREDENTIALS_MISSING)
                : Optional.empty();
    }
}
