package com.supplymind.scheduling;

import com.supplymind.scheduling.api.CurrentAcquisitionController;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CurrentAcquisitionServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-21T01:00:00Z"), ZoneOffset.ofHours(8));

    @Test
    void successfulCyclePublishesBusinessDateWithoutBlockingCaller() {
        PbocDay2CollectionService cycle = mock(PbocDay2CollectionService.class);
        when(cycle.runImmediateCycle()).thenReturn(result());
        CurrentAcquisitionService service = new CurrentAcquisitionService(cycle, CLOCK, Runnable::run);

        CurrentAcquisitionService.Status status = service.trigger();

        assertEquals("SUCCEEDED", status.state());
        assertEquals("2026-08-20", status.businessDate());
        assertEquals("2026-08-21T09:00+08:00", status.updatedAt());
    }

    @Test
    void sourceFailureIsControlledAndDoesNotExposeExceptionDetail() {
        PbocDay2CollectionService cycle = mock(PbocDay2CollectionService.class);
        when(cycle.runImmediateCycle()).thenThrow(new IllegalStateException("secret transport detail"));
        CurrentAcquisitionService service = new CurrentAcquisitionService(cycle, CLOCK, Runnable::run);

        CurrentAcquisitionService.Status status = service.trigger();

        assertEquals("FAILED", status.state());
        assertNull(status.businessDate());
        assertEquals("Official PBOC rates are temporarily unavailable", status.message());
    }

    @Test
    void concurrentRefreshDoesNotStartASecondCycleAndControllerReturnsAccepted() {
        PbocDay2CollectionService cycle = mock(PbocDay2CollectionService.class);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        Executor delayed = queued::set;
        CurrentAcquisitionService service = new CurrentAcquisitionService(cycle, CLOCK, delayed);
        CurrentAcquisitionController controller = new CurrentAcquisitionController(service);

        assertEquals(HttpStatus.ACCEPTED, controller.refresh().getStatusCode());
        assertEquals("RUNNING", controller.refresh().getBody().state());
        verifyNoInteractions(cycle);

        when(cycle.runImmediateCycle()).thenReturn(result());
        queued.get().run();
        assertEquals("SUCCEEDED", controller.status().state());
    }

    private static Day2CycleResult result() {
        return new Day2CycleResult(
                "2026-08-20", "acq", "sha", "usd-run", "eur-run",
                "usd-raw", "eur-raw", "6.7808", "7.8815",
                "usd-daily", "eur-daily", List.of("usd-month"), List.of("eur-month"), 1, 1);
    }
}
