package com.supplymind.validation;

import com.supplymind.foundation.model.CandidateV1;

/** Exactly one of candidate or rejectionReasonCode is non-null. */
public record StandardizationResult(CandidateV1 candidate, String rejectionReasonCode) {
}
