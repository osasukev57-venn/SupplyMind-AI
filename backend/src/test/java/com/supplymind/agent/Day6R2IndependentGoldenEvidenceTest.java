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

    private static final String GOLDEN_SHA256 = "0e66319d3d0eb9064bfbdedb6d1a3ad9af19a56ed83a2affdfa035f9705d6c56";

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
