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
import org.junit.jupiter.api.io.TempDir;
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
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1 production-path proof: the guarded scheduler is the ONLY formal {@code @Scheduled}
 * acquisition entry, and its actual scheduled method passes through the rotation guard before
 * any collection/publish/process work. A real Spring context (with {@code @EnableScheduling})
 * is booted with the production {@link RotationGuardConfiguration}; the collection boundary is
 * the real {@link PbocDay2CollectionService} over a fixture transport (no network), so every
 * guarded invocation that actually runs performs the real acquisition -&gt; raw -&gt; validation
 * -&gt; publish -&gt; daily -&gt; aggregate chain. Rollback scheduled invocations are suppressed
 * and never duplicate collection/publish/process work.
 */
class ScheduledGuardProductionPathTest {

    private static final URI DETAIL_URI = URI.create(
            "https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/fixture-announcement-20260810.html");
    private static final String FIXTURE_ROOT = "contracts/v1/d1-t04-pboc/";

    @TempDir
    Path temporaryDirectory;

    @Test
    void guardedScheduledEntryIsTheOnlyScheduledAcquisitionEntryAndSuppressesRollback() throws Exception {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("scheduler-test", Map.of(
                "supplymind.scheduler.guarded-enabled", "true")));
        context.register(TestSchedulingConfig.class);
        context.refresh();

        try {
            RotationGuardConfiguration.RotationGuardedScheduler scheduler =
                    context.getBean(RotationGuardConfiguration.RotationGuardedScheduler.class);
            PbocDay2Scheduler legacy = context.getBean(PbocDay2Scheduler.class);
            SettableClock clock = context.getBean(SettableClock.class);
            CountingTransport transport = context.getBean(CountingTransport.class);

            List<Method> scheduledMethods = scheduledMethodsAcrossContext(context);
            assertEquals(1, scheduledMethods.size(),
                    "the application must expose exactly ONE formal @Scheduled acquisition entry: "
                            + scheduledMethods);
            assertEquals("runGuardedCycle", scheduledMethods.get(0).getName(),
                    "the single scheduled entry must be the rotation-guarded cycle");
            assertTrue(scheduledMethods.get(0).getDeclaringClass().getName().contains("RotationGuardedScheduler"));
            assertTrue(Arrays.stream(PbocDay2Scheduler.class.getDeclaredMethods())
                            .noneMatch(method -> method.isAnnotationPresent(Scheduled.class)),
                    "the legacy scheduler must not carry a @Scheduled acquisition entry");
            assertTrue(Arrays.stream(legacy.getClass().getDeclaredMethods())
                            .noneMatch(method -> method.isAnnotationPresent(Scheduled.class)),
                    "the legacy scheduler bean in the context must not be scheduled");

            clock.set(at("2026-08-31T23:59:50+08:00"));
            scheduler.runGuardedCycle();
            assertEquals(2, transport.callCount(), "Aug31 scheduled invocation must run exactly one collection cycle");
            assertEquals("2026-08", timeState(context).lastCompletedPeriod());

            clock.set(at("2026-09-01T00:00:10+08:00"));
            scheduler.runGuardedCycle();
            assertEquals(4, transport.callCount(), "Sep1 scheduled invocation must run exactly one more cycle");
            TimeStateV1 afterSeptember = timeState(context);
            assertEquals("2026-09", afterSeptember.lastCompletedPeriod());
            assertEquals(java.time.LocalDate.parse("2026-09-01"), afterSeptember.effectiveBusinessDate());

            clock.set(at("2026-08-31T12:00:00+08:00"));
            scheduler.runGuardedCycle();
            assertEquals(4, transport.callCount(),
                    "a rollback scheduled invocation must NOT trigger collection/publish/process");
            TimeStateV1 afterRollback = timeState(context);
            assertEquals(afterSeptember.effectiveHighWaterTime(), afterRollback.effectiveHighWaterTime(),
                    "the rollback invocation must not move the high-water mark");

            clock.set(at("2026-09-01T10:00:00+08:00"));
            scheduler.runGuardedCycle();
            assertEquals(6, transport.callCount(), "recover scheduled invocation runs exactly one cycle");
            assertEquals("2026-09", timeState(context).lastCompletedPeriod(),
                    "recover must not duplicate the September month rotation");

            clock.set(at("2026-09-02T10:00:00+08:00"));
            scheduler.runGuardedCycle();
            assertEquals(8, transport.callCount(), "Sep2 scheduled invocation runs exactly one cycle");
            assertEquals("2026-09", timeState(context).lastCompletedPeriod(),
                    "Sep2 must not repeat the month rotation");
            assertEquals(java.time.LocalDate.parse("2026-09-02"), timeState(context).effectiveBusinessDate());
        } finally {
            context.close();
        }
    }

    private static TimeStateV1 timeState(AnnotationConfigApplicationContext context) {
        return context.getBean(TimeStateStore.class).read();
    }

    private static List<Method> scheduledMethodsAcrossContext(AnnotationConfigApplicationContext context) {
        List<Method> scheduled = new ArrayList<>();
        for (String beanName : context.getBeanDefinitionNames()) {
            Object bean = context.getBean(beanName);
            if (bean == null) {
                continue;
            }
            for (Method method : bean.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(Scheduled.class)) {
                    scheduled.add(method);
                }
            }
        }
        return scheduled;
    }

    @Configuration
    @EnableScheduling
    @Import(RotationGuardConfiguration.class)
    static class TestSchedulingConfig {
        private static final Instant CLOCK_EPOCH = Instant.parse("2026-08-31T15:59:50Z");

        @Bean
        SettableClock foundationClock() {
            return new SettableClock(CLOCK_EPOCH);
        }

        @Bean
        DataRoot dataRoot() {
            DataRoot root;
            try {
                root = DataRoot.forTest(java.nio.file.Files.createTempDirectory("supplymind-scheduler-test-"));
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Unable to create scheduler test root", exception);
            }
            AtomicMoveSupport.probeOrFail(root);
            root.path().toFile().deleteOnExit();
            return root;
        }

        @Bean
        AtomicFileStore atomicFileStore(DataRoot dataRoot) {
            return new AtomicFileStore(dataRoot, new DirtyMarkerCodec());
        }

        @Bean
        ConfigActivationStore configActivationStore(DataRoot dataRoot, AtomicFileStore fileStore, SettableClock clock) {
            ConfigActivationStore store = new ConfigActivationStore(dataRoot, fileStore, clock);
            MonitorSeriesConfigV1 initial = store.ensureInitialDefault();
            Objects.requireNonNull(initial, "initial config");
            return store;
        }

        @Bean
        TimeStateStore timeStateStore(DataRoot dataRoot, AtomicFileStore fileStore, SettableClock clock) {
            return new TimeStateStore(dataRoot, fileStore, clock);
        }

        @Bean
        TimeRotationService timeRotationService(TimeStateStore timeStateStore) {
            return new TimeRotationService(timeStateStore);
        }

        @Bean
        RawReceiptStore rawReceiptStore(DataRoot dataRoot, AtomicFileStore fileStore, SettableClock clock) {
            return new RawReceiptStore(dataRoot, fileStore, clock);
        }

        @Bean
        RawAcquisitionStore rawAcquisitionStore(DataRoot dataRoot, AtomicFileStore fileStore, SettableClock clock) {
            return new RawAcquisitionStore(dataRoot, fileStore, clock);
        }

        @Bean
        TimelineStore timelineStore(DataRoot dataRoot, AtomicFileStore fileStore, SettableClock clock) {
            return new TimelineStore(dataRoot, fileStore, clock);
        }

        @Bean
        QuarantineStore quarantineStore(DataRoot dataRoot, AtomicFileStore fileStore, SettableClock clock) {
            return new QuarantineStore(dataRoot, fileStore, clock);
        }

        @Bean
        LifecycleValidationService lifecycleValidationService(DataRoot dataRoot, TimelineStore timelineStore,
                                                              SettableClock clock) {
            return new LifecycleValidationService(dataRoot, timelineStore, clock);
        }

        @Bean
        LifecyclePublishService lifecyclePublishService(DataRoot dataRoot, TimelineStore timelineStore,
                                                        QuarantineStore quarantineStore, SettableClock clock) {
            return new LifecyclePublishService(dataRoot, timelineStore, quarantineStore, clock);
        }

        @Bean
        DailyProcessingService dailyProcessingService(DataRoot dataRoot, TimelineStore timelineStore,
                                                      AtomicFileStore fileStore, SettableClock clock) {
            return new DailyProcessingService(dataRoot, timelineStore, fileStore, clock);
        }

        @Bean
        AggregateProcessingService aggregateProcessingService(DataRoot dataRoot, AtomicFileStore fileStore,
                                                              SettableClock clock) {
            return new AggregateProcessingService(dataRoot, fileStore, clock);
        }

        @Bean
        PbocHttpTransport pbocHttpTransport() {
            return new CountingTransport(fixtureBytes("announcement-list-normal.html"),
                    fixtureBytes("announcement-detail-normal.html"));
        }

        @Bean
        PbocAnnouncementParser pbocAnnouncementParser() {
            return new PbocAnnouncementParser();
        }

        @Bean
        PbocOfficialWebDataProvider pbocOfficialWebDataProvider(
                DataRoot dataRoot, RawReceiptStore rawReceiptStore, RawAcquisitionStore rawAcquisitionStore,
                AtomicFileStore atomicFileStore, SettableClock clock,
                PbocHttpTransport transport, PbocAnnouncementParser parser
        ) {
            return new PbocOfficialWebDataProvider(dataRoot, rawReceiptStore, rawAcquisitionStore, atomicFileStore,
                    clock, transport, parser, event -> { });
        }

        @Bean
        PbocDay2CollectionService pbocDay2CollectionService(
                PbocOfficialWebDataProvider provider, TimelineStore timelineStore,
                LifecycleValidationService validation, LifecyclePublishService publish,
                DailyProcessingService daily, AggregateProcessingService aggregate
        ) {
            return new PbocDay2CollectionService(provider, timelineStore, validation, publish, daily, aggregate);
        }

        @Bean
        PbocDay2Scheduler pbocDay2Scheduler(PbocDay2CollectionService collectionService) {
            return new PbocDay2Scheduler(collectionService);
        }
    }

    static final class SettableClock extends Clock {
        private volatile Instant instant;

        SettableClock(Instant instant) {
            this.instant = instant;
        }

        SettableClock(OffsetDateTime now) {
            this.instant = now.toInstant();
        }

        void set(OffsetDateTime now) {
            this.instant = now.toInstant();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    static final class CountingTransport implements PbocHttpTransport {
        private final byte[] listEntity;
        private final byte[] detailEntity;
        private int callCount;

        CountingTransport(byte[] listEntity, byte[] detailEntity) {
            this.listEntity = listEntity;
            this.detailEntity = detailEntity;
        }

        @Override
        public PbocHttpResponse get(URI uri) {
            callCount++;
            if (uri.equals(PbocOfficialWebDataProvider.ANNOUNCEMENT_LIST_URI)) {
                return new PbocHttpResponse(uri, 200, "text/html; charset=UTF-8", listEntity);
            }
            if (uri.equals(DETAIL_URI)) {
                return new PbocHttpResponse(uri, 200, "text/html; charset=UTF-8", detailEntity);
            }
            throw new AssertionError("Unexpected production-path request: " + uri);
        }

        int callCount() {
            return callCount;
        }
    }

    private static byte[] fixtureBytes(String name) {
        try (InputStream stream = Objects.requireNonNull(
                ScheduledGuardProductionPathTest.class.getClassLoader().getResourceAsStream(FIXTURE_ROOT + name),
                () -> "Missing production-path fixture " + name)) {
            return stream.readAllBytes();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Unable to read production-path fixture " + name, exception);
        }
    }

    private static OffsetDateTime at(String value) {
        return OffsetDateTime.parse(value);
    }
}
