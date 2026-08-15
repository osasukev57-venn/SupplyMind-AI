package com.supplymind.agent;

import com.supplymind.agent.support.Day6R2Fixture;
import com.supplymind.agent.tool.ToolStatus;
import com.supplymind.processing.CostImpactCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Independent M6 attack: fixed decimal vectors plus an actual persisted cost.impact tool call. */
class Day6R2IndependentCostAttackTest {

    @TempDir
    Path temp;

    @Test
    void warningAndAgentPathsBothReferenceTheSingleProductionCalculator() throws Exception {
        String adapter = Files.readString(Path.of("src/main/java/com/supplymind/agent/infrastructure/springai/CostImpactToolAdapter.java"));
        String warning = Files.readString(Path.of("src/main/java/com/supplymind/warning/WarningService.java"));
        assertEquals(1, occurrences(adapter, "CostImpactCalculator\\s*\\.\\s*changeRatio"));
        assertFalse(adapter.contains(".divide("), "Agent adapter must not own a change-ratio formula");
        assertEquals(1, occurrences(warning, "CostImpactCalculator\\s*\\.\\s*changeRatio"));
    }

    @Test
    void costImpactUsesExactBigDecimalProductionSemanticsForFixedVectors() {
        List<Vector> vectors = List.of(
                new Vector("110", "100", "0.100000000000"),
                new Vector("90", "100", "-0.100000000000"),
                new Vector("1.000000000001", "1", "0.000000000001"),
                new Vector("0", "100", "-1.000000000000"));
        for (Vector vector : vectors) {
            assertEquals(vector.expected, CostImpactCalculator.changeRatio(
                    new BigDecimal(vector.current), new BigDecimal(vector.previous)).toPlainString());
        }
    }

    @Test
    void actualCostImpactToolMatchesIndependentFixedExpectedRatio() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "cost-tool");
        var result = fixture.costImpact().costImpact(Day6R2Fixture.ITEM, "month", "2026-08-01", "cost-r2");
        assertEquals(ToolStatus.SUCCESS, result.status());
        assertEquals("0.234567890100", result.result().get("changeRatio"));
        assertEquals("123.45678901", result.result().get("currentAvg"));
        assertEquals("100.00000000", result.result().get("previousAvg"));
    }

    private static int occurrences(String text, String token) {
        return (int) java.util.regex.Pattern.compile(token).matcher(text).results().count();
    }

    private record Vector(String current, String previous, String expected) {
    }
}
