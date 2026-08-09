package com.supplymind.validation;

import com.supplymind.foundation.model.ValidationStatus;

/** D2-T01 deterministic verdict; reasonCode is non-null exactly when the frozen matrix requires it. */
public record ValidationVerdict(ValidationStatus validationStatus, String reasonCode) {
}
