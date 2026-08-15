package com.supplymind.agent;

import com.supplymind.agent.support.Day6R2Fixture;
import com.supplymind.foundation.codec.JsonV1Codec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Fixed A11 oracle: resource bytes and SHA are hand-frozen, never produced at assertion time. */
class Day6R2IndependentGoldenEvidenceTest {

    private static final String GOLDEN_SHA256 = "5f18e2b7b63d20fae8fd4d6ad8ec4c259b1f678548ecc6d113f7fa3310d0e5df";

    @TempDir
    Path temp;

    @Test
    void evidencePackMatchesFixedGoldenBytesAndSecondIndependentBuildIsByteIdentical() throws Exception {
        byte[] golden = Files.readAllBytes(Path.of("src/test/resources/contracts/v1/d6-r2/evidence-pack-golden.json"));
        assertEquals(GOLDEN_SHA256, sha256(golden));

        byte[] first = JsonV1Codec.encodeFile(Day6R2Fixture.create(temp, "golden-first")
                .verifiedEvidencePack("golden"));
        byte[] second = JsonV1Codec.encodeFile(Day6R2Fixture.create(temp, "golden-second")
                .verifiedEvidencePack("golden"));
        assertArrayEquals(golden, first, "A11 fixed schema/order/encoding contract");
        assertArrayEquals(golden, second, "A11 repeated logical input must be byte-identical");
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) hex.append(String.format("%02x", value));
        return hex.toString();
    }
}
