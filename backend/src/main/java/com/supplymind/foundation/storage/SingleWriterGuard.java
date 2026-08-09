package com.supplymind.foundation.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Process-wide writer ownership for the sole dataRoot. The lock is a runtime
 * coordination artifact in the already frozen runtime/dirty directory; it is
 * neither business data nor a second data root and never receives a manifest.
 */
public final class SingleWriterGuard implements AutoCloseable {

    private static final String LOCK_REF = "runtime/dirty/.supplymind-writer.lock";

    private final FileChannel channel;
    private final FileLock lock;

    private SingleWriterGuard(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static SingleWriterGuard acquire(DataRoot dataRoot) {
        Path lockPath = dataRoot.resolveInternalRelative(LOCK_REF);
        try {
            Files.createDirectories(lockPath.getParent());
            FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            try {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    channel.close();
                    throw new StorageException("Another SupplyMind writer already owns dataRoot: " + dataRoot.path());
                }
                return new SingleWriterGuard(channel, lock);
            } catch (OverlappingFileLockException exception) {
                channel.close();
                throw new StorageException("Another SupplyMind writer already owns dataRoot: " + dataRoot.path(), exception);
            }
        } catch (IOException exception) {
            throw new StorageException("Unable to acquire the sole dataRoot writer lock", exception);
        }
    }

    @Override
    public void close() {
        try {
            if (lock.isValid()) {
                lock.release();
            }
            channel.close();
        } catch (IOException exception) {
            throw new StorageException("Unable to release the dataRoot writer lock", exception);
        }
    }
}