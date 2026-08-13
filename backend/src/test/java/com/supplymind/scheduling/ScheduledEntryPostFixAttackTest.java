package com.supplymind.scheduling;

import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.TimeStateV1;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.QuarantineStore;
import com.supplymind.foundation.storage.RawAcquisitionStore;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.TimeStateStore;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.processing.AggregateProcessingService;
import com.supplymind.processing.DailyProcessingService;
import com.supplymind.provider.pboc.PbocAnnouncementParser;
import com.supplymind.provider.pboc.PbocHttpResponse;
import com.supplymind.provider.pboc.PbocHttpTransport;
import com.supplymind.provider.pboc.PbocOfficialWebDataProvider;
import com.supplymind.publish.LifecyclePublishService;
import com.supplymind.rotation.TimeRotationService;
import com.supplymind.validation.LifecycleValidationService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent M1 attack: invoke the real Spring {@code @Scheduled} bean method, never the guard
 * directly, while observing real fixture-backed PBOC acquisition and persisted processing bytes.
 */
class ScheduledEntryPostFixAttackTest {

    private static final URI DETAIL_URI = URI.create(
            "https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/fixture-announcement-20260810.html");

    @Test
    void everyFormalScheduledCollectionEntryIsGuardedAndRollbackCannotDuplicateProcessing() throws Exception {
        try (AnnotationConfigApplicationContext context = context()) {
            List<Method> scheduled = scheduledMethods(context);
            assertEquals(1, scheduled.size(), "there must be exactly one formal production @Scheduled entry");
            Method entry = scheduled.get(0);
            assertEquals("runGuardedCycle", entry.getName());
            assertEquals(RotationGuardConfiguration.RotationGuardedScheduler.class, entry.getDeclaringClass());
            assertFalse(Arrays.stream(PbocDay2Scheduler.class.getDeclaredMethods())
                    .anyMatch(method -> method.isAnnotationPresent(Scheduled.class)),
                    "legacy PBOC scheduler must never directly schedule runImmediateCycle");

            RotationGuardConfiguration.RotationGuardedScheduler scheduler = context.getBean(
                    RotationGuardConfiguration.RotationGuardedScheduler.class);
            SettableClock clock = context.getBean(SettableClock.class);
            CountingTransport transport = context.getBean(CountingTransport.class);
            TimeStateStore timeStates = context.getBean(TimeStateStore.class);

            clock.set(at("2026-08-31T23:59:50+08:00"));
            invoke(entry, scheduler);
            assertEquals(2, transport.calls(), "the actual scheduled bean must issue one real list/detail collection");

            clock.set(at("2026-09-01T00:00:10+08:00"));
            invoke(entry, scheduler);
            assertEquals(4, transport.calls(), "first Sep1 scheduled trigger must execute exactly once");
            TimeStateV1 september = timeStates.read();
            assertEquals("2026-09", september.lastCompletedPeriod());
            assertEquals(java.time.LocalDate.parse("2026-09-01"), september.effectiveBusinessDate());
            Map<String, byte[]> afterSeptember = processingSnapshot(context.getBean(DataRoot.class));

            clock.set(at("2026-08-31T12:00:00+08:00"));
            invoke(entry, scheduler);
            assertEquals(4, transport.calls(), "rollback scheduled invocation must be SUPPRESSED before collection");
            TimeStateV1 rollback = timeStates.read();
            assertEquals(september.effectiveHighWaterTime(), rollback.effectiveHighWaterTime());
            assertEquals(september.effectiveBusinessDate(), rollback.effectiveBusinessDate());
            assertEquals(september.lastCompletedPeriod(), rollback.lastCompletedPeriod());
            assertBusinessSnapshot(afterSeptember, processingSnapshot(context.getBean(DataRoot.class)));

            clock.set(at("2026-09-01T10:00:00+08:00"));
            invoke(entry, scheduler);
            assertEquals(6, transport.calls(), "recovery is a real scheduled collection attempt, not a second rotation");
            assertEquals("2026-09", timeStates.read().lastCompletedPeriod());
            assertBusinessSnapshot(afterSeptember, processingSnapshot(context.getBean(DataRoot.class)));

            clock.set(at("2026-09-02T10:00:00+08:00"));
            invoke(entry, scheduler);
            assertEquals(8, transport.calls());
            TimeStateV1 finalState = timeStates.read();
            assertEquals("2026-09", finalState.lastCompletedPeriod());
            assertEquals(java.time.LocalDate.parse("2026-09-02"), finalState.effectiveBusinessDate());
            assertBusinessSnapshot(afterSeptember, processingSnapshot(context.getBean(DataRoot.class)),
                    "recovery and Sep2 must not duplicate raw/timeline/daily/aggregate business artifacts");
        }
    }

    private static AnnotationConfigApplicationContext context() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("post-fix attack", Map.of(
                "supplymind.scheduler.guarded-enabled", "true")));
        context.register(AttackConfiguration.class);
        context.refresh();
        return context;
    }

    private static List<Method> scheduledMethods(AnnotationConfigApplicationContext context) {
        List<Method> result = new ArrayList<>();
        for (String beanName : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(beanName);
            if (type == null) continue;
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Scheduled.class)) result.add(method);
            }
        }
        return result;
    }

    private static void invoke(Method scheduledEntry, Object scheduler) throws Exception {
        scheduledEntry.setAccessible(true);
        scheduledEntry.invoke(scheduler);
    }

    private static Map<String, byte[]> processingSnapshot(DataRoot root) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String topLevel : List.of("raw", "staging", "processed")) {
            Path directory = root.resolveInternalRelative(topLevel);
            if (!Files.isDirectory(directory)) continue;
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    if (!path.getFileName().toString().endsWith(".manifest.json")) {
                        files.put(root.toDataRef(path), Files.readAllBytes(path));
                    }
                }
            }
        }
        return files;
    }

    private static void assertBusinessSnapshot(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertBusinessSnapshot(expected, actual, "no duplicate business processing artifacts");
    }

    private static void assertBusinessSnapshot(Map<String, byte[]> expected, Map<String, byte[]> actual, String message) {
        assertEquals(expected.keySet(), actual.keySet(), message);
        expected.forEach((ref, bytes) -> assertArrayEquals(bytes, actual.get(ref), message + " at " + ref));
    }

    @Configuration
    @EnableScheduling
    @Import(RotationGuardConfiguration.class)
    static class AttackConfiguration {
        @Bean SettableClock foundationClock() { return new SettableClock(at("2026-08-31T23:59:50+08:00")); }
        @Bean DataRoot dataRoot() {
            try {
                DataRoot root = DataRoot.forTest(Files.createTempDirectory("supplymind-r2-scheduled-"));
                AtomicMoveSupport.probeOrFail(root);
                return root;
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        @Bean AtomicFileStore atomicFileStore(DataRoot root) { return new AtomicFileStore(root, new DirtyMarkerCodec()); }
        @Bean ConfigActivationStore configActivationStore(DataRoot root, AtomicFileStore files, SettableClock clock) {
            ConfigActivationStore store = new ConfigActivationStore(root, files, clock);
            MonitorSeriesConfigV1 ignored = store.ensureInitialDefault();
            return store;
        }
        @Bean TimeStateStore timeStateStore(DataRoot root, AtomicFileStore files, SettableClock clock) {
            return new TimeStateStore(root, files, clock);
        }
        @Bean TimeRotationService timeRotationService(TimeStateStore store) { return new TimeRotationService(store); }
        @Bean RawReceiptStore rawReceiptStore(DataRoot root, AtomicFileStore files, SettableClock clock) {
            return new RawReceiptStore(root, files, clock);
        }
        @Bean RawAcquisitionStore rawAcquisitionStore(DataRoot root, AtomicFileStore files, SettableClock clock) {
            return new RawAcquisitionStore(root, files, clock);
        }
        @Bean TimelineStore timelineStore(DataRoot root, AtomicFileStore files, SettableClock clock) {
            return new TimelineStore(root, files, clock);
        }
        @Bean QuarantineStore quarantineStore(DataRoot root, AtomicFileStore files, SettableClock clock) {
            return new QuarantineStore(root, files, clock);
        }
        @Bean LifecycleValidationService lifecycleValidationService(DataRoot root, TimelineStore timelines, SettableClock clock) {
            return new LifecycleValidationService(root, timelines, clock);
        }
        @Bean LifecyclePublishService lifecyclePublishService(DataRoot root, TimelineStore timelines,
                                                               QuarantineStore quarantine, SettableClock clock) {
            return new LifecyclePublishService(root, timelines, quarantine, clock);
        }
        @Bean DailyProcessingService dailyProcessingService(DataRoot root, TimelineStore timelines, AtomicFileStore files,
                                                             SettableClock clock) {
            return new DailyProcessingService(root, timelines, files, clock);
        }
        @Bean AggregateProcessingService aggregateProcessingService(DataRoot root, AtomicFileStore files,
                                                                      SettableClock clock) {
            return new AggregateProcessingService(root, files, clock);
        }
        @Bean PbocHttpTransport pbocHttpTransport() { return new CountingTransport(bytes("announcement-list-normal.html"), bytes("announcement-detail-normal.html")); }
        @Bean PbocAnnouncementParser pbocAnnouncementParser() { return new PbocAnnouncementParser(); }
        @Bean PbocOfficialWebDataProvider pbocOfficialWebDataProvider(DataRoot root, RawReceiptStore raw,
                RawAcquisitionStore acquisition, AtomicFileStore files, SettableClock clock,
                PbocHttpTransport transport, PbocAnnouncementParser parser) {
            return new PbocOfficialWebDataProvider(root, raw, acquisition, files, clock, transport, parser, event -> { });
        }
        @Bean PbocDay2CollectionService pbocDay2CollectionService(PbocOfficialWebDataProvider provider,
                TimelineStore timelines, LifecycleValidationService validation, LifecyclePublishService publish,
                DailyProcessingService daily, AggregateProcessingService aggregate) {
            return new PbocDay2CollectionService(provider, timelines, validation, publish, daily, aggregate);
        }
        @Bean PbocDay2Scheduler pbocDay2Scheduler(PbocDay2CollectionService service) { return new PbocDay2Scheduler(service); }
    }

    static final class SettableClock extends Clock {
        private volatile Instant instant;
        SettableClock(OffsetDateTime initial) { instant = initial.toInstant(); }
        void set(OffsetDateTime now) { instant = now.toInstant(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    static final class CountingTransport implements PbocHttpTransport {
        private final byte[] list;
        private final byte[] detail;
        private int calls;
        CountingTransport(byte[] list, byte[] detail) { this.list = list; this.detail = detail; }
        @Override public PbocHttpResponse get(URI uri) {
            calls++;
            if (uri.equals(PbocOfficialWebDataProvider.ANNOUNCEMENT_LIST_URI)) return new PbocHttpResponse(uri, 200, "text/html; charset=UTF-8", list);
            if (uri.equals(DETAIL_URI)) return new PbocHttpResponse(uri, 200, "text/html; charset=UTF-8", detail);
            throw new AssertionError("unexpected formal scheduled acquisition URI: " + uri);
        }
        int calls() { return calls; }
    }

    private static byte[] bytes(String name) {
        try (InputStream stream = Objects.requireNonNull(ScheduledEntryPostFixAttackTest.class.getClassLoader()
                .getResourceAsStream("contracts/v1/d1-t04-pboc/" + name))) {
            return stream.readAllBytes();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static OffsetDateTime at(String value) { return OffsetDateTime.parse(value); }
}
