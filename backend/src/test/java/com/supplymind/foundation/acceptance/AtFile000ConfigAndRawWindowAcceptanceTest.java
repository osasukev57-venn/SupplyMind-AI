package com.supplymind.foundation.acceptance;

import com.supplymind.foundation.storage.AtomicFileRecovery;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.DirtyMarkerV1;
import com.supplymind.foundation.storage.DirtyTargetPhase;
import com.supplymind.foundation.storage.DirtyTargetRole;
import com.supplymind.foundation.storage.DirtyTargetV1;
import com.supplymind.foundation.storage.DirtyTransactionPhase;
import com.supplymind.foundation.storage.DirtyTransactionType;
import com.supplymind.foundation.storage.ManifestVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent crash-window evidence for AT-FILE-000 step 6.  The config and
 * raw bytes are frozen test/contract fixtures; manifest expectations are
 * manually rendered with JDK SHA-256 rather than ManifestFactory.
 */
class AtFile000ConfigAndRawWindowAcceptanceTest {

    private static final String FIXTURE_ROOT = "contracts/v1/valid/";
    private static final OffsetDateTime TIME = OffsetDateTime.parse("2026-08-10T09:04:00+08:00");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T01:04:00Z"), ZoneOffset.UTC);
    private static final String RAW_REF = "raw/test/synthetic_demo/FX.USD.CNY.CONTRACT_FIXTURE/2026/08/"
            + "run-fixture-usd-0001.json";
    private static final String RAW_SHA256 = "98f4a7cf87e6020d82450a826208311e417c26058086b32817951d15e9cb9551";

    @TempDir
    Path temporaryDirectory;

    @Test
    void completesEveryConfigActivationFourPhysicalFileCrashWindow() throws Exception {
        byte[] priorActiveBytes = fixtureBytes("monitor-series-v1.json");
        byte[] configurationBytes = nextConfigurationVersionBytes(priorActiveBytes);
        String configurationSha256 = sha256(configurationBytes);
        String historyRef = DataPaths.configHistoryRef(2);
        String activeRef = DataPaths.configActiveRef();

        for (int completedPhysicalFiles = 1; completedPhysicalFiles <= 4; completedPhysicalFiles++) {
            DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("config-window-" + completedPhysicalFiles));
            root.createIfAbsentAndRequireWritable();
            String transactionId = "review-config-window-" + completedPhysicalFiles;
            DirtyMarkerV1 marker = configMarker(
                    transactionId, historyRef, activeRef, configurationSha256, sha256(priorActiveBytes), completedPhysicalFiles
            );
            Path historyPath = root.resolveDataRef(historyRef);
            Path historyManifestPath = root.resolveDataRef(DataPaths.manifestRef(historyRef));
            Path activePath = root.resolveDataRef(activeRef);
            Path activeManifestPath = root.resolveDataRef(DataPaths.manifestRef(activeRef));
            Path activeBackup = activePath.resolveSibling(
                    DataPaths.adjacentBackupFileName(activePath.getFileName().toString(), transactionId));

            if (completedPhysicalFiles >= 1) {
                write(historyPath, configurationBytes);
            }
            if (completedPhysicalFiles >= 2) {
                write(historyManifestPath, manualJsonManifest(
                        historyPath.getFileName().toString(), configurationBytes, TIME));
            }
            write(activePath, priorActiveBytes);
            if (completedPhysicalFiles >= 3) {
                Files.move(activePath, activeBackup);
                write(activePath, configurationBytes);
            }
            if (completedPhysicalFiles >= 4) {
                write(activeManifestPath, manualJsonManifest(
                        activePath.getFileName().toString(), configurationBytes, TIME));
            }
            DirtyMarkerCodec codec = new DirtyMarkerCodec();
            Path markerPath = root.resolveDataRef(DataPaths.dirtyMarkerRef(transactionId));
            write(markerPath, codec.encode(marker));

            List<DirtyMarkerV1> recovered = new AtomicFileRecovery(root, codec, CLOCK).recoverAll();

            assertEquals(1, recovered.size(), "exactly one marker must be reconciled for window " + completedPhysicalFiles);
            assertEquals(6, recovered.get(0).markerRevision());
            assertEquals(DirtyTransactionPhase.COMMITTED, recovered.get(0).transactionPhase());
            assertArrayEquals(configurationBytes, Files.readAllBytes(historyPath));
            assertArrayEquals(configurationBytes, Files.readAllBytes(activePath));
            assertTrue(ManifestVerifier.matches(root, historyRef, historyPath, historyManifestPath));
            assertTrue(ManifestVerifier.matches(root, activeRef, activePath, activeManifestPath));
            assertFalse(Files.exists(markerPath), "marker may be removed only after all four physical files reconcile");
            assertFalse(Files.exists(activeBackup), "a completed activation cleans its marker-attributable active backup");
        }
    }

    @Test
    void completesMarkerProvenRawTemporaryFileBeforeDataCommitWithoutChangingFixtureBytes() throws Exception {
        byte[] rawBytes = fixtureBytes("raw-receipt-v1.json");
        assertEquals(RAW_SHA256, sha256(rawBytes), "JDK SHA-256 validates the frozen raw fixture");
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("raw-temporary-window"));
        root.createIfAbsentAndRequireWritable();

        String transactionId = "review-raw-temporary-001";
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
        );
        Path rawPath = root.resolveDataRef(RAW_REF);
        Path rawTemporary = rawPath.resolveSibling(
                DataPaths.adjacentTemporaryFileName(rawPath.getFileName().toString(), transactionId));
        write(rawTemporary, rawBytes);
        DirtyMarkerCodec codec = new DirtyMarkerCodec();
        Path markerPath = root.resolveDataRef(DataPaths.dirtyMarkerRef(transactionId));
        write(markerPath, codec.encode(marker));

        List<DirtyMarkerV1> recovered = new AtomicFileRecovery(root, codec, CLOCK).recoverAll();

        assertEquals(1, recovered.size());
        assertEquals(4, recovered.get(0).markerRevision());
        assertEquals(DirtyTransactionPhase.COMMITTED, recovered.get(0).transactionPhase());
        assertArrayEquals(rawBytes, Files.readAllBytes(rawPath));
        assertTrue(ManifestVerifier.matches(root, RAW_REF, rawPath, root.resolveDataRef(DataPaths.manifestRef(RAW_REF))));
        assertFalse(Files.exists(rawTemporary));
        assertFalse(Files.exists(markerPath));
    }

    private static DirtyMarkerV1 configMarker(
            String transactionId,
            String historyRef,
            String activeRef,
            String configurationSha256,
            String activeOldSha256,
            int completedPhysicalFiles
    ) {
        DirtyMarkerV1 marker = DirtyMarkerV1.open(
                transactionId,
                DirtyTransactionType.CONFIG_ACTIVATION,
                TIME,
                List.of(
                        new DirtyTargetV1(1, DirtyTargetRole.CONFIG_HISTORY, historyRef, DataPaths.manifestRef(historyRef),
                                configurationSha256, null, DirtyTargetPhase.PREPARED),
                        new DirtyTargetV1(2, DirtyTargetRole.CONFIG_ACTIVE, activeRef, DataPaths.manifestRef(activeRef),
                                configurationSha256, activeOldSha256, DirtyTargetPhase.PREPARED)
                )
        );
        if (completedPhysicalFiles >= 1) {
            marker = marker.advanceTarget(1, DirtyTargetPhase.DATA_COMMITTED);
        }
        if (completedPhysicalFiles >= 2) {
            marker = marker.advanceTarget(1, DirtyTargetPhase.MANIFEST_COMMITTED);
        }
        if (completedPhysicalFiles >= 3) {
            marker = marker.advanceTarget(2, DirtyTargetPhase.DATA_COMMITTED);
        }
        if (completedPhysicalFiles >= 4) {
            marker = marker.advanceTarget(2, DirtyTargetPhase.MANIFEST_COMMITTED);
        }
        return marker;
    }

    private static byte[] nextConfigurationVersionBytes(byte[] priorActiveBytes) {
        String previous = new String(priorActiveBytes, StandardCharsets.UTF_8);
        String next = previous
                .replace("\"configVersion\": 1", "\"configVersion\": 2")
                .replace("\"updatedAt\": \"2026-08-01T00:00:00+08:00\"",
                        "\"updatedAt\": \"2026-08-10T09:04:00+08:00\"");
        if (next.equals(previous)) {
            throw new AssertionError("The frozen configuration fixture did not contain the explicit v1 fields");
        }
        return next.getBytes(StandardCharsets.UTF_8);
    }
    private static byte[] manualJsonManifest(String fileName, byte[] dataBytes, OffsetDateTime generatedAt) {
        String manifest = "{\"schemaVersion\":\"1.0\",\"fileName\":\"" + fileName
                + "\",\"fileSha256\":\"" + sha256(dataBytes)
                + "\",\"byteLength\":" + dataBytes.length
                + ",\"rowCount\":null,\"minBusinessDate\":null,\"maxBusinessDate\":null,"
                + "\"sourceRunIds\":[],\"generatedAt\":\"" + generatedAt
                + "\",\"commitState\":\"COMMITTED\"}\n";
        return manifest.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] fixtureBytes(String fixtureName) throws IOException {
        try (InputStream stream = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(FIXTURE_ROOT + fixtureName),
                () -> "Missing frozen test/contract fixture " + fixtureName
        )) {
            return stream.readAllBytes();
        }
    }

    private static void write(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
