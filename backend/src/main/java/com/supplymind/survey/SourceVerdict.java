package com.supplymind.survey;

/** D3-T03 verdict of one free-public source investigation. */
public enum SourceVerdict {
    APPROVED,
    NOT_APPROVED,
    /**
     * Neutral, evidence-factual verdict: this investigation obtained insufficient public
     * page facts (e.g. a TLS/HTTPS handshake or connection failure in this environment) to
     * confirm the source capability. It is not a claim about the site's permanent interface;
     * it only means no approval can be granted from current evidence.
     */
    UNVERIFIED
}
