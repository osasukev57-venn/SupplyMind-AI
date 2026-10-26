package com.supplymind.agent.evidence;

import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AggregateRecordV1;
import com.supplymind.foundation.model.CanonicalJsonV1;
import com.supplymind.foundation.storage.FileDigest;
import java.util.*;

/** Full decoded group identity + canonical single-row CSV digest; never a row number. */
public record AggregateRowEvidenceV1(String schemaVersion, String rowEvidenceId,
        String originalRef, String fileSha256, String manifestSha256, String rowSha256,
        AggregateRecordV1 row) {
    public AggregateRowEvidenceV1 {
        if(!"AGGREGATE-ROW-EVIDENCE-V1".equals(schemaVersion))throw new IllegalArgumentException("row evidence version");
        Objects.requireNonNull(row);
        if(!rowId(row).equals(rowEvidenceId) || !digest(row).equals(rowSha256))throw new IllegalArgumentException("row identity/digest mismatch");
        com.supplymind.foundation.storage.DataPaths.requireLegalDataRef(originalRef);
        if(!fileSha256.matches("[a-f0-9]{64}")||!manifestSha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("row file digest");
    }
    public static String rowId(AggregateRecordV1 r) {
        // Fixed ordered JSON vector. configVersions is row lineage, NOT a grouping field.
        var key=Arrays.asList("AGGREGATE-GROUP-IDENTITY-V1",r.itemId(),r.providerType().wireValue(),r.actualSourceName(),
                r.accessMethod().wireValue(),r.sourceFingerprint(),r.canonicalSpecCode(),r.grain().wireValue(),
                r.periodStart(),r.periodEnd(),r.validationStatus().wireValue(),r.validationVersion(),
                r.calculationVersion(),r.calculationScale(),r.displayScale(),r.roundingMode().name(),
                r.calendarVersion(),r.currency(),r.unit());
        return "aggregate-row-"+CanonicalJsonV1.sha256LowerHex(JsonV1Codec.encodeCompact(key));
    }
    public static String digest(AggregateRecordV1 row) {
        // Frozen codec UTF-8, full header + one canonical decoded row, CRLF, no BOM.
        return FileDigest.sha256(CsvV1Codec.encodeAggregate(List.of(row)));
    }
}
