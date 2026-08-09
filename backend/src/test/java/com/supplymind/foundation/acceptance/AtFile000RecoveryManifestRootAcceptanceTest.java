package com.supplymind.foundation.acceptance;

import com.supplymind.foundation.storage.AtomicFileRecovery;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.DirtyMarkerRecovery;
import com.supplymind.foundation.storage.DirtyMarkerV1;
import com.supplymind.foundation.storage.DirtyTargetPhase;
import com.supplymind.foundation.storage.DirtyTargetRole;
import com.supplymind.foundation.storage.DirtyTargetV1;
import com.supplymind.foundation.storage.DirtyTransactionType;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sol review evidence for AT-FILE-000 steps 1, 6, and 7.  Expected fixture
 * bytes and SHA-256 values are deliberately fixed here; no production codec
 * or ManifestFactory is used to construct the expected manifest contract.
 */
class AtFile000RecoveryManifestRootAcceptanceTest {

    private static final String FIXTURE_ROOT = "contracts/v1/valid/";
    private static final String RAW_FIXTURE = "raw-receipt-v1.json";
    private static final String MANIFEST_FIXTURE = "manifest-raw-receipt-v1.json";
    private static final String RAW_REF = "raw/test/synthetic_demo/FX.USD.CNY.CONTRACT_FIXTURE/2026/08/"
            + "run-fixture-usd-0001.json";
    private static final String RAW_SHA256 = "98f4a7cf87e6020d82450a826208311e417c26058086b32817951d15e9cb9551";
    private static final int RAW_BYTE_LENGTH = 1221;
    private static final String RUN_ID = "run-fixture-usd-0001";
    private static final OffsetDateTime TIME = OffsetDateTime.parse("2026-08-10T09:04:00+08:00");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T01:04:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void fixedManifestTamperMatrixFailsClosedAgainstFrozenRawBytes() throws Exception {
        byte[] rawBytes = fixtureBytes(RAW_FIXTURE);
        byte[] manifestBytes = fixtureBytes(MANIFEST_FIXTURE);
        assertEquals(RAW_BYTE_LENGTH, rawBytes.length);
        assertEquals(RAW_SHA256, sha256(rawBytes), "JDK digest validates the frozen raw fixture independently");

        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("manifest 篡改 matrix"));
        root.createIfAbsentAndRequireWritable();
        Path rawPath = root.resolveDataRef(RAW_REF);
        Path manifestPath = root.resolveDataRef(DataPaths.manifestRef(RAW_REF));
        Files.createDirectories(rawPath.getParent());
        Files.write(rawPath, rawBytes);
        Files.createDirectories(manifestPath.getParent());
        Files.write(manifestPath, manifestBytes);
        assertTrue(ManifestVerifier.matches(root, RAW_REF, rawPath, manifestPath));

        String canonicalManifest = new String(manifestBytes, StandardCharsets.UTF_8);
        List<Tamper> cases = List.of(
                new Tamper("fileSha256", canonicalManifest.replace(RAW_SHA256, "0".repeat(64))),
                new Tamper("rowCount", canonicalManifest.replace("\"rowCount\": null", "\"rowCount\": 1")),
                new Tamper("dateRange", canonicalManifest.replace(
                        "\"minBusinessDate\": null,\n  \"maxBusinessDate\": null",
                        "\"minBusinessDate\": \"2026-08-10\",\n  \"maxBusinessDate\": \"2026-08-10\"")),
                new Tamper("sourceRunIds", canonicalManifest.replace(
                        "\"run-fixture-usd-0001\"\n  ]", "\"run-fixture-tampered-0001\"\n  ]"))
        );
        for (Tamper tamper : cases) {
            Files.writeString(manifestPath, tamper.manifest(), StandardCharsets.UTF_8);
            assertFalse(ManifestVerifier.matches(root, RAW_REF, rawPath, manifestPath),
                    () -> "manual " + tamper.name() + " tamper must fail closed");
        }
    }

    @Test
    void markerProvenNewRawRebuildsOnlyItsMissingManifestFromFixedRawContract() throws Exception {
        byte[] rawBytes = fixtureBytes(RAW_FIXTURE);
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("raw missing manifest recovery"));
        root.createIfAbsentAndRequireWritable();
        Path rawPath = root.resolveDataRef(RAW_REF);
        Files.createDirectories(rawPath.getParent());
        Files.write(rawPath, rawBytes);

        String transactionId = "review-raw-missing-manifest-001";
        DirtyTargetV1 target = new DirtyTargetV1(
                1,
                DirtyTargetRole.BUSINESS_FILE,
                RAW_REF,
                DataPaths.manifestRef(RAW_REF),
                RAW_SHA256,
                null,
                DirtyTargetPhase.PREPARED
        );
        DirtyMarkerV1 marker = DirtyMarkerV1.open(
                transactionId, DirtyTransactionType.SINGLE_FILE, TIME, List.of(target)
        ).advanceTarget(1, DirtyTargetPhase.DATA_COMMITTED);
        DirtyMarkerCodec codec = new DirtyMarkerCodec();
        Path markerPath = root.resolveDataRef(DataPaths.dirtyMarkerRef(transactionId));
        Files.createDirectories(markerPath.getParent());
        Files.write(markerPath, codec.encode(marker));

        List<DirtyMarkerV1> recovered = new AtomicFileRecovery(root, codec, CLOCK).recoverAll();

        Path manifestPath = root.resolveDataRef(DataPaths.manifestRef(RAW_REF));
        assertEquals(1, recovered.size());
        assertArrayEquals(rawBytes, Files.readAllBytes(rawPath), "recovery must not rewrite immutable raw bytes");
        assertTrue(ManifestVerifier.matches(root, RAW_REF, rawPath, manifestPath));
        String rebuilt = Files.readString(manifestPath, StandardCharsets.UTF_8);
        assertTrue(rebuilt.contains("\"fileName\":\"run-fixture-usd-0001.json\""));
        assertTrue(rebuilt.contains("\"fileSha256\":\"" + RAW_SHA256 + "\""));
        assertTrue(rebuilt.contains("\"byteLength\":1221"));
        assertTrue(rebuilt.contains("\"rowCount\":null"));
        assertTrue(rebuilt.contains("\"sourceRunIds\":[\"" + RUN_ID + "\"]"));
        assertFalse(Files.exists(markerPath), "marker is cleaned only after raw plus manifest reconciliation");
    }

    @Test
    void markerFieldDriftAndAllInvalidCandidatesFailClosedWithEvidencePreserved() throws Exception {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("marker ambiguity"));
        root.createIfAbsentAndRequireWritable();
        DirtyMarkerCodec codec = new DirtyMarkerCodec();
        String transactionId = "review-marker-drift-001";
        DirtyTargetV1 initialTarget = new DirtyTargetV1(
                1, DirtyTargetRole.BUSINESS_FILE, "staging/review-marker-run.json",
                "staging/review-marker-run.json.manifest.json", "a".repeat(64), null, DirtyTargetPhase.PREPARED
        );
        DirtyMarkerV1 initial = DirtyMarkerV1.open(transactionId, DirtyTransactionType.SINGLE_FILE, TIME, List.of(initialTarget));
        DirtyTargetV1 driftedTarget = new DirtyTargetV1(
                1, DirtyTargetRole.BUSINESS_FILE, initialTarget.dataRef(), initialTarget.manifestRef(),
                "b".repeat(64), null, DirtyTargetPhase.DATA_COMMITTED
        );
        DirtyMarkerV1 driftedRevision = new DirtyMarkerV1(
                DirtyMarkerV1.SCHEMA_VERSION, transactionId, DirtyTransactionType.SINGLE_FILE, TIME,
                2, com.supplymind.foundation.storage.DirtyTransactionPhase.OPEN, List.of(driftedTarget)
        );
        Path canonical = root.resolveDataRef(DataPaths.dirtyMarkerRef(transactionId));
        Path temporary = root.resolveInternalRelative(DataPaths.dirtyMarkerTemporaryRef(transactionId));
        Files.createDirectories(canonical.getParent());
        Files.write(canonical, codec.encode(initial));
        Files.write(temporary, codec.encode(driftedRevision));

        assertThrows(StorageException.class, () -> new DirtyMarkerRecovery(codec).recoverCanonicalMarkers(root));
        assertTrue(Files.exists(canonical));
        assertTrue(Files.exists(temporary));

        String invalidTransactionId = "review-marker-invalid-001";
        Path invalidCanonical = root.resolveDataRef(DataPaths.dirtyMarkerRef(invalidTransactionId));
        Files.writeString(invalidCanonical, "not-a-dirty-marker\n", StandardCharsets.UTF_8);
        assertThrows(StorageException.class, () -> new DirtyMarkerRecovery(codec).recoverCanonicalMarkers(root));
        assertTrue(Files.exists(invalidCanonical), "all-invalid marker evidence must be retained for manual review");
    }

    @Test
    void readOnlyImageAndAtomicMoveUnsupportedFileSystemFailFastWithoutFallbackRoot() throws Exception {
        FileSystem image = FileSystems.getFileSystem(URI.create("jrt:/"));
        DataRoot readOnlyRoot = DataRoot.forTest(image.getPath("/modules"));
        assertThrows(RuntimeException.class, readOnlyRoot::createIfAbsentAndRequireWritable,
                "a read-only image path must fail rather than create another dataRoot");

        Path zip = temporaryDirectory.resolve("atomic-move-unsupported.zip");
        try (FileSystem zipFileSystem = FileSystems.newFileSystem(URI.create("jar:" + zip.toUri()), Map.of("create", "true"))) {
            Path source = temporaryDirectory.resolve("cross-provider-source.tmp");
            Path target = zipFileSystem.getPath("/supplymind/cross-provider-target.tmp");
            Files.writeString(source, "AT-FILE-000 cross-provider move probe\n", StandardCharsets.UTF_8);
            Files.createDirectories(target.getParent());
            assertThrows(StorageException.class, () -> AtomicMoveSupport.moveToEmptyTarget(source, target),
                    "a cross-provider ATOMIC_MOVE must fail fast with no non-atomic fallback");
            assertTrue(Files.exists(source), "failed atomic move must preserve the source bytes for recovery");
        }
        assertFalse(Files.exists(temporaryDirectory.resolve("data")), "tests must not silently create a second default root");
    }

    private byte[] fixtureBytes(String name) throws IOException {
        try (InputStream stream = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(FIXTURE_ROOT + name),
                () -> "Missing frozen test/contract fixture " + name)) {
            return stream.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder text = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                text.append(String.format("%02x", value));
            }
            return text.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private record Tamper(String name, String manifest) {
    }
}
