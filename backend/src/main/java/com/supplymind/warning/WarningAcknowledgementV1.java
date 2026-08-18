package com.supplymind.warning;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.supplymind.foundation.model.ModelRules;
import com.supplymind.foundation.model.SchemaValidationException;

import java.time.OffsetDateTime;
import java.time.YearMonth;

/**
 * DEC-061 frozen warning acknowledgement sidecar. The original WarningRecordV1 at
 * data/warning/YYYY-MM/&lt;warningId&gt;.json is PERMANENTLY immutable; an acknowledgement is a
 * SEPARATE sidecar document at data/warning/YYYY-MM/&lt;warningId&gt;.ack.json binding to the
 * original warning file's identity (warningRef + warningFileSha256) and the server-generated
 * acknowledgement time. status v1 only allows ACKNOWLEDGED; dispositionNote is a controlled,
 * length-limited text that must not carry paths or secrets.
 */
@JsonPropertyOrder({
        "schemaVersion", "warningId", "warningRef", "warningFileSha256",
        "status", "acknowledgedAt", "dispositionNote"
})
public record WarningAcknowledgementV1(
        String schemaVersion,
        String warningId,
        String warningRef,
        String warningFileSha256,
        AckStatus status,
        OffsetDateTime acknowledgedAt,
        String dispositionNote
) {
    public enum AckStatus {
        ACKNOWLEDGED
    }

    /** DEC-061: dispositionNote is a controlled text - bounded length, no path/secret shapes. */
    public static final int MAX_DISPOSITION_NOTE_LENGTH = 500;

    public WarningAcknowledgementV1 {
        ModelRules.schemaVersion(schemaVersion);
        ModelRules.id(warningId, "warningId");
        ModelRules.relativeDataRef(warningRef, "warningRef");
        ModelRules.sha256(warningFileSha256, "warningFileSha256");
        if (status != AckStatus.ACKNOWLEDGED) {
            throw new SchemaValidationException("status v1 only allows ACKNOWLEDGED");
        }
        ModelRules.dateTime(acknowledgedAt, "acknowledgedAt");
        if (dispositionNote == null || dispositionNote.isBlank()) {
            throw new SchemaValidationException("dispositionNote must not be blank");
        }
        if (dispositionNote.length() > MAX_DISPOSITION_NOTE_LENGTH) {
            throw new SchemaValidationException(
                    "dispositionNote must not exceed " + MAX_DISPOSITION_NOTE_LENGTH + " characters");
        }
        if (dispositionNote.indexOf('/') >= 0 || dispositionNote.indexOf('\\') >= 0
                || dispositionNote.contains("..") || dispositionNote.contains(":")
                || dispositionNote.contains(";") || dispositionNote.contains("\n")
                || dispositionNote.contains("\r")) {
            throw new SchemaValidationException(
                    "dispositionNote must be plain controlled text (no paths, separators, colon, semicolon or newline)");
        }
    }

    /** Month under which the sidecar file is persisted (warning/YYYY-MM). */
    public YearMonth warningMonth() {
        String prefix = "warning/";
        return YearMonth.parse(warningRef.substring(prefix.length(), prefix.length() + 7));
    }
}
