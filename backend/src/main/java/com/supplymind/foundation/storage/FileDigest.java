package com.supplymind.foundation.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 and forced-write helpers for files governed by a DirtyMarkerV1. */
public final class FileDigest {

    private static final int BUFFER_SIZE = 16 * 1024;

    private FileDigest() {
    }

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    public static String sha256(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new StorageException("Unable to calculate SHA-256 for " + path, exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    public static void writeCreateNewAndForce(Path target, byte[] bytes) {
        try {
            Files.createDirectories(target.getParent());
            try (FileChannel channel = FileChannel.open(target,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
        } catch (IOException exception) {
            throw new StorageException("Unable to create and force " + target, exception);
        }
    }

    public static byte[] utf8(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    public static boolean bytesEqual(Path path, byte[] expected) {
        try {
            return Files.exists(path) && MessageDigest.isEqual(Files.readAllBytes(path), expected);
        } catch (IOException exception) {
            throw new StorageException("Unable to compare bytes for " + path, exception);
        }
    }

    public static boolean isLowerHexSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
