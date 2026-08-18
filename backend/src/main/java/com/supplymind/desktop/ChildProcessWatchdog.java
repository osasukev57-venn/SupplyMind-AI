package com.supplymind.desktop;

import java.time.Duration;

/**
 * D9-T04 parent-process watchdog. When the Electron shell is force-killed (Task Manager,
 * crash), normal shutdown hooks never run. This component polls the Electron parent PID
 * and exits the backend when the parent is gone, so a Java process never lingers.
 *
 * <p>Strictly opt-in: it is only created when {@code --supplymind.desktop.parent-pid=<pid>}
 * is passed explicitly by the Electron shell. Normal (dev) startup never passes it, so this
 * component has zero effect on the existing Day1-Day8 runtime contract.</p>
 */
public final class ChildProcessWatchdog implements AutoCloseable {

    private final long parentPid;
    private final Runnable onParentGone;
    private final Duration pollInterval;
    private volatile boolean running;
    private Thread thread;

    /**
     * @param parentPid the Electron main-process PID to watch
     * @param onParentGone action to run when the parent is no longer alive (default exit)
     * @param pollInterval how often to check liveness
     */
    public ChildProcessWatchdog(long parentPid, Runnable onParentGone, Duration pollInterval) {
        if (parentPid <= 0) {
            throw new IllegalArgumentException("parent-pid must be a positive pid");
        }
        if (parentPid == ProcessHandle.current().pid()) {
            throw new IllegalArgumentException("parent-pid must not be the backend's own pid");
        }
        this.parentPid = parentPid;
        this.onParentGone = onParentGone;
        this.pollInterval = pollInterval;
    }

    /** True while the watched parent process is still alive (or resolvable). */
    public boolean isParentAlive() {
        return ProcessHandle.of(parentPid).map(ProcessHandle::isAlive).orElse(false);
    }

    public synchronized void start() {
        if (thread != null) {
            return;
        }
        running = true;
        thread = new Thread(this::runLoop, "supplymind-parent-watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    private void runLoop() {
        while (running) {
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!isParentAlive()) {
                running = false;
                onParentGone.run();
                return;
            }
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }
}
