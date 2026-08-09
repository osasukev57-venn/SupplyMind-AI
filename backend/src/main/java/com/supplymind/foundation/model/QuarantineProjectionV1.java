package com.supplymind.foundation.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/** Non-authoritative, reconstructable projection of one of the three failed terminal states. */
@JsonPropertyOrder({
        "schemaVersion", "quarantineRef", "itemId", "runId", "rawRef", "stagingRef", "terminalRecordVersion",
        "processingStage", "validationStatus", "reasonCode", "validationVersion", "rawPayloadSha256",
        "rawFileSha256", "receivedAt", "quarantinedAt"
})
public record QuarantineProjectionV1(
        String schemaVersion,
        String quarantineRef,
        String itemId,
        String runId,
        String rawRef,
        String stagingRef,
        int terminalRecordVersion,
        ProcessingStage processingStage,
        ValidationStatus validationStatus,
        String reasonCode,
        String validationVersion,
        String rawPayloadSha256,
        String rawFileSha256,
        OffsetDateTime receivedAt,
        OffsetDateTime quarantinedAt
) {
    private static final ZoneId ASIA_SHANGHAI = ZoneId.of("Asia/Shanghai");

    public QuarantineProjectionV1 {
        ModelRules.schemaVersion(schemaVersion);
        ModelRules.id(itemId, "itemId");
        ModelRules.id(runId, "runId");
        ModelRules.relativeDataRef(rawRef, "rawRef");
        ModelRules.relativeDataRef(stagingRef, "stagingRef");
        ModelRules.positive(terminalRecordVersion, "terminalRecordVersion");
        ModelRules.required(processingStage, "processingStage");
        ModelRules.required(validationStatus, "validationStatus");
        ModelRules.nonBlank(reasonCode, "reasonCode");
        ModelRules.sha256(rawPayloadSha256, "rawPayloadSha256");
        ModelRules.sha256(rawFileSha256, "rawFileSha256");
        ModelRules.dateTime(receivedAt, "receivedAt");
        ModelRules.dateTime(quarantinedAt, "quarantinedAt");

        boolean receivedRejected = processingStage == ProcessingStage.RECEIVED
                && validationStatus == ValidationStatus.REJECTED;
        boolean validatedTerminal = processingStage == ProcessingStage.VALIDATED
                && (validationStatus == ValidationStatus.REJECTED || validationStatus == ValidationStatus.CONFLICT);
        if (!receivedRejected && !validatedTerminal) {
            throw new SchemaValidationException("QuarantineProjectionV1 only permits the three failed terminal states");
        }
        if (receivedRejected && validationVersion != null) {
            throw new SchemaValidationException("RECEIVED+REJECTED quarantine must have validationVersion=null");
        }
        if (validatedTerminal) {
            ModelRules.nonBlank(validationVersion, "validationVersion");
        }
        String expectedStagingRef = "staging/" + runId + ".json";
        if (!expectedStagingRef.equals(stagingRef)) {
            throw new SchemaValidationException("stagingRef must be the run's canonical staging reference");
        }
        String expectedQuarantineRef = deriveQuarantineRef(itemId, receivedAt, runId);
        if (!expectedQuarantineRef.equals(quarantineRef)) {
            throw new SchemaValidationException("quarantineRef must be routed from receivedAt in Asia/Shanghai");
        }
    }

    public static String deriveQuarantineRef(String itemId, OffsetDateTime receivedAt, String runId) {
        ModelRules.id(itemId, "itemId");
        ModelRules.dateTime(receivedAt, "receivedAt");
        ModelRules.id(runId, "runId");
        var inShanghai = receivedAt.atZoneSameInstant(ASIA_SHANGHAI);
        return "quarantine/" + itemId + "/" + String.format("%04d-%02d", inShanghai.getYear(), inShanghai.getMonthValue())
                + "/" + runId + ".json";
    }

    public static QuarantineProjectionV1 fromTerminal(
            RawReceiptV1 rawReceipt,
            LifecycleTimelineV1 timeline,
            String rawFileSha256
    ) {
        ModelRules.required(rawReceipt, "rawReceipt");
        ModelRules.required(timeline, "timeline");
        if (!rawReceipt.runId().equals(timeline.runId()) || !rawReceipt.rawRef().equals(timeline.rawRef())) {
            throw new SchemaValidationException("RawReceipt and LifecycleTimeline must refer to the same run/rawRef");
        }
        LifecycleSnapshotV1 terminal = timeline.current();
        return new QuarantineProjectionV1(
                SchemaV1.VERSION,
                deriveQuarantineRef(rawReceipt.itemId(), rawReceipt.receivedAt(), rawReceipt.runId()),
                rawReceipt.itemId(),
                rawReceipt.runId(),
                rawReceipt.rawRef(),
                "staging/" + rawReceipt.runId() + ".json",
                terminal.recordVersion(),
                terminal.processingStage(),
                terminal.validationStatus(),
                terminal.reasonCode(),
                terminal.validationVersion(),
                rawReceipt.payloadSha256(),
                rawFileSha256,
                rawReceipt.receivedAt(),
                terminal.updatedAt()
        );
    }
}
