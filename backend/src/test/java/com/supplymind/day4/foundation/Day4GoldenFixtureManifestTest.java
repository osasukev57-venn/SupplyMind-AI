package com.supplymind.day4.foundation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GD-01..GD-07 fixture integrity.  The oracle is SHA256SUMS plus JDK MessageDigest, never a
 * production codec or digest helper.  This is fixture readiness, not material-chain acceptance.
 */
class Day4GoldenFixtureManifestTest {

    private static final String ROOT = "contracts/v1/day4/golden/";
    private static final List<String> FIXTURES = List.of(
            "GD-01.json", "GD-02.json", "GD-03.json", "GD-04.json",
            "GD-05.json", "GD-06.json", "GD-07.json");

    @Test
    void gd01ThroughGd07HaveSortedStableIndependentSha256Manifest() throws Exception {
        List<String> expectedNames = new ArrayList<>(FIXTURES);
        expectedNames.sort(String::compareTo);
        assertEquals(expectedNames, FIXTURES, "fixture source list must be deterministic and filename-sorted");

        List<String> lines = List.of(resourceText("SHA256SUMS").trim().split("\\n"));
        assertEquals(FIXTURES.size(), lines.size(), "manifest must enumerate each, and only each, GD fixture");

        List<String> names = new ArrayList<>();
        for (String line : lines) {
            String[] fields = line.split("  ", -1);
            assertEquals(2, fields.length, "SHA256SUMS uses '<lowercase sha256><two spaces><filename>'");
            assertTrue(fields[0].matches("[0-9a-f]{64}"), "digest is lowercase SHA-256");
            names.add(fields[1]);
            assertEquals(sha256(resourceBytes(fields[1])), fields[0],
                    () -> "fixture content changed without a matching manifest digest: " + fields[1]);
        }
        assertEquals(FIXTURES, names, "manifest order must not depend on filesystem traversal");
    }

    @Test
    void fixturesPreserveFrozenReadinessAndDoNotClaimFutureMaterialAcceptance() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        for (String name : FIXTURES) {
            JsonNode root = mapper.readTree(resourceBytes(name));
            assertEquals(name.substring(0, 5), root.required("fixtureId").asText());
            assertEquals("TEST_CONTRACT_FIXTURE", root.required("fixtureKind").asText());
            assertTrue(root.required("notProductionEvidence").asBoolean());
        }
        for (String name : List.of("GD-05.json", "GD-06.json", "GD-07.json")) {
            JsonNode root = mapper.readTree(resourceBytes(name));
            assertEquals("PENDING_IMPLEMENTATION", root.required("executionState").asText(),
                    name + " must not fabricate a material-chain PASS before D4 implementation");
        }
        String gd07 = resourceText("GD-07.json");
        assertTrue(gd07.contains("PENDING_APPROVED_SOURCE"));
        assertFalse(gd07.contains("https://"), "GD-07 must not invent an unapproved FreePublic source URL");
    }

    private static byte[] resourceBytes(String name) throws IOException {
        try (InputStream input = Objects.requireNonNull(
                Day4GoldenFixtureManifestTest.class.getClassLoader().getResourceAsStream(ROOT + name),
                () -> "Missing Day4 golden fixture " + name)) {
            return input.readAllBytes();
        }
    }

    private static String resourceText(String name) throws IOException {
        return new String(resourceBytes(name), StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
