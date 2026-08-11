package com.supplymind.day4.foundation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DEC-058 stage-reconciliation guard.  It makes the three Day 4 source acceptance obligations
 * visible without fabricating a material raw, validation, publication, daily, or aggregate PASS.
 */
class AtSrcDay4ReconciliationHarnessTest {

    @Test
    void day4SourceSubcasesRemainNotRunUntilTheirRealMaterialChainsProduceEvidence() {
        List<StageCase> cases = List.of(
                new StageCase("AT-SRC-005-D4", "NOT_RUN", "non-synthetic material raw -> VALIDATED -> VERIFIED class"),
                new StageCase("AT-SRC-007-D4", "NOT_RUN", "Manual validation -> PUBLISHED -> daily -> aggregate"),
                new StageCase("AT-SRC-008-D4", "NOT_RUN", "material daily/aggregate provenance reconciliation"));

        assertEquals(List.of("AT-SRC-005-D4", "AT-SRC-007-D4", "AT-SRC-008-D4"),
                cases.stream().map(StageCase::caseId).toList());
        assertTrue(cases.stream().allMatch(item -> "NOT_RUN".equals(item.acceptanceStatus())));
        assertTrue(cases.stream().noneMatch(item -> "PASS".equals(item.acceptanceStatus())));
    }

    private record StageCase(String caseId, String acceptanceStatus, String requiredRealChain) {
    }
}
