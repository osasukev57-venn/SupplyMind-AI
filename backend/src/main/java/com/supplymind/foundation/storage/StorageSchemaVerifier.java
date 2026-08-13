package com.supplymind.foundation.storage;

import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.QuarantineProjectionV1;
import com.supplymind.foundation.model.RawAcquisitionV1;
import com.supplymind.foundation.model.RawReceiptV1;

/** Re-reads known D1-T03 business documents before an atomic target becomes visible. */
final class StorageSchemaVerifier {

    private StorageSchemaVerifier() {
    }

    static void verifyData(String dataRef, byte[] bytes) {
        if (dataRef.equals(DataPaths.configActiveRef()) || dataRef.startsWith("config/history/")) {
            MonitorSeriesConfigV1 configuration = JsonV1Codec.decodeFile(bytes, MonitorSeriesConfigV1.class);
            if (dataRef.startsWith("config/history/")
                    && !dataRef.equals(DataPaths.configHistoryRef(configuration.configVersion()))) {
                throw new StorageException("config/history reference must match MonitorSeriesConfigV1.configVersion: "
                        + dataRef);
            }
        } else if (dataRef.startsWith("raw/import/")) {
            com.supplymind.localimport.LocalImportReceiptV1 receipt = JsonV1Codec.decodeFile(
                    bytes, com.supplymind.localimport.LocalImportReceiptV1.class);
            if (!dataRef.equals(receipt.importRef())) {
                throw new StorageException("LocalImportReceiptV1.importRef must match its atomic target: " + dataRef);
            }
        } else if (dataRef.startsWith("raw/source/")) {
            RawAcquisitionV1 acquisition = JsonV1Codec.decodeFile(bytes, RawAcquisitionV1.class);
            if (!dataRef.equals(acquisition.acquisitionRef())) {
                throw new StorageException("RawAcquisitionV1.acquisitionRef must match its atomic target: " + dataRef);
            }
        } else if (dataRef.startsWith("raw/")) {
            RawReceiptV1 receipt = JsonV1Codec.decodeFile(bytes, RawReceiptV1.class);
            if (!dataRef.equals(receipt.rawRef())) {
                throw new StorageException("RawReceiptV1.rawRef must match its atomic target: " + dataRef);
            }
        } else if (dataRef.startsWith("staging/")) {
            LifecycleTimelineV1 timeline = JsonV1Codec.decodeFile(bytes, LifecycleTimelineV1.class);
            if (!dataRef.equals(DataPaths.stagingRef(timeline.runId()))) {
                throw new StorageException("LifecycleTimelineV1.runId must match its staging atomic target: " + dataRef);
            }
        } else if (dataRef.startsWith("quarantine/")) {
            QuarantineProjectionV1 quarantine = JsonV1Codec.decodeFile(bytes, QuarantineProjectionV1.class);
            if (!dataRef.equals(quarantine.quarantineRef())) {
                throw new StorageException("QuarantineProjectionV1.quarantineRef must match its atomic target: " + dataRef);
            }
        } else if (dataRef.startsWith("runtime/conflicts/raw/")) {
            RawConflictEvidenceV1 conflict = JsonV1Codec.decodeFile(bytes, RawConflictEvidenceV1.class);
            String expected = DataPaths.rawConflictRef(
                    conflict.itemId(), conflict.incomingReceipt().receivedAt(), conflict.runId(), conflict.conflictId());
            if (!dataRef.equals(expected)) {
                throw new StorageException("RawConflictEvidenceV1 identity must match its atomic target: " + dataRef);
            }
        } else if (dataRef.startsWith("processed/daily/")) {
            CsvV1Codec.decodeDaily(bytes);
        } else if (dataRef.startsWith("processed/aggregate/")) {
            CsvV1Codec.decodeAggregate(bytes);
        } else if (dataRef.startsWith("runtime/jobs/active/time-state.json")) {
            com.supplymind.foundation.model.TimeStateV1 state = JsonV1Codec.decodeFile(
                    bytes, com.supplymind.foundation.model.TimeStateV1.class);
            if (!dataRef.equals(DataPaths.timeStateRef())) {
                throw new StorageException("TimeStateV1 target must be the frozen time-state ref: " + dataRef);
            }
        } else if (dataRef.startsWith("runtime/jobs/active/")) {
            com.supplymind.backfill.BackfillJobStateV1 job = JsonV1Codec.decodeFile(
                    bytes, com.supplymind.backfill.BackfillJobStateV1.class);
            if (!dataRef.equals(DataPaths.backfillJobRef(job.jobId()))) {
                throw new StorageException("BackfillJobStateV1.jobId must match its atomic target: " + dataRef);
            }
        } else if (dataRef.startsWith("warning/")) {
            com.supplymind.warning.WarningRecordV1 warning = JsonV1Codec.decodeFile(
                    bytes, com.supplymind.warning.WarningRecordV1.class);
            if (!dataRef.equals(DataPaths.warningRef(warning.warningMonth(), warning.warningId()))) {
                throw new StorageException("WarningRecordV1 identity must match its atomic target: " + dataRef);
            }
        } else {
            throw new StorageException("No D1-T03 schema verifier is registered for atomic target " + dataRef);
        }
    }
}