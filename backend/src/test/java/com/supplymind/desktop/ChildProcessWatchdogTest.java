package com.supplymind.desktop;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D9-T04 watchdog tests. The watchdog must be strictly opt-in (positive foreign pid), must
 * detect a dead parent and must not fire while the parent is alive.
 */
class ChildProcessWatchdogTest {

    @Test
    void rejectsNonPositivePid() {
        assertThatThrownBy(() -> new ChildProcessWatchdog(0, () -> {}, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("parent-pid must be a positive pid");
        assertThatThrownBy(() -> new ChildProcessWatchdog(-1, () -> {}, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("parent-pid must be a positive pid");
    }

    @Test
    void rejectsOwnPid() {
        long self = ProcessHandle.current().pid();
        assertThatThrownBy(() -> new ChildProcessWatchdog(self, () -> {}, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("parent-pid must not be the backend's own pid");
    }

    @Test
    void considersCurrentProcessAlive() throws Exception {
        // A long-lived helper process stands in for the live Electron parent.
        Process live = spawnLiveHelper();
        try {
            ChildProcessWatchdog watchdog = new ChildProcessWatchdog(
                    live.pid(), () -> {}, Duration.ofSeconds(1));
            assertThat(watchdog.isParentAlive()).isTrue();
        } finally {
            live.destroyForcibly();
        }
    }

    @Test
    void considersUnknownPidDead() {
        // Max pid on Windows is bounded well below this; the pid can never exist.
        ChildProcessWatchdog watchdog = new ChildProcessWatchdog(4_000_000_000L, () -> {},
                Duration.ofSeconds(1));
        assertThat(watchdog.isParentAlive()).isFalse();
    }

    @Test
    void firesActionWhenParentDisappears() throws Exception {
        // A child JVM that exits immediately simulates a killed Electron parent.
        Process child = new ProcessBuilder("java", "-version")
                .redirectErrorStream(true)
                .start();
        child.waitFor();
        long deadPid = child.pid();

        AtomicBoolean fired = new AtomicBoolean(false);
        ChildProcessWatchdog watchdog = new ChildProcessWatchdog(
                deadPid, () -> fired.set(true), Duration.ofMillis(50));
        watchdog.start();
        Thread.sleep(400);
        watchdog.close();

        assertThat(fired).isTrue();
    }

    @Test
    void doesNotFireWhileParentAlive() throws Exception {
        Process live = spawnLiveHelper();
        try {
            AtomicInteger fired = new AtomicInteger(0);
            ChildProcessWatchdog watchdog = new ChildProcessWatchdog(
                    live.pid(), fired::incrementAndGet, Duration.ofMillis(50));
            watchdog.start();
            Thread.sleep(400);
            watchdog.close();
            assertThat(fired).hasValue(0);
        } finally {
            live.destroyForcibly();
        }
    }

    @Test
    void closeStopsTheLoop() throws Exception {
        Process live = spawnLiveHelper();
        try {
            AtomicInteger fired = new AtomicInteger(0);
            ChildProcessWatchdog watchdog = new ChildProcessWatchdog(
                    live.pid(), fired::incrementAndGet, Duration.ofMillis(20));
            watchdog.start();
            Thread.sleep(100);
            watchdog.close();
            int before = fired.get();
            Thread.sleep(200);
            assertThat(fired).hasValue(before);
        } finally {
            live.destroyForcibly();
        }
    }

    private static Process spawnLiveHelper() throws Exception {
        // A long-lived helper stands in for the live Electron parent (ping -t runs until killed).
        return new ProcessBuilder("cmd", "/c", "ping", "-t", "127.0.0.1")
                .redirectErrorStream(true)
                .start();
    }
}
