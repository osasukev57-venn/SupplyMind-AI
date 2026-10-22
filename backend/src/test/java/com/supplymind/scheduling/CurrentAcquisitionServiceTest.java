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
        ActiveCurrentCollectionService cycle = mock(ActiveCurrentCollectionService.class);
        when(cycle.runImmediateCycle()).thenReturn(result());
        CurrentAcquisitionService service = new CurrentAcquisitionService(cycle, CLOCK, Runnable::run);

        CurrentAcquisitionService.Status status = service.trigger();

        assertEquals("SUCCEEDED", status.state());
        assertEquals("2026-08-20", status.businessDate());
        assertEquals("2026-08-21T09:00+08:00", status.updatedAt());
    }

    @Test
    void sourceFailureIsControlledAndDoesNotExposeExceptionDetail() {
        ActiveCurrentCollectionService cycle = mock(ActiveCurrentCollectionService.class);
        when(cycle.runImmediateCycle()).thenThrow(new IllegalStateException("secret transport detail"));
        CurrentAcquisitionService service = new CurrentAcquisitionService(cycle, CLOCK, Runnable::run);

        CurrentAcquisitionService.Status status = service.trigger();

        assertEquals("FAILED", status.state());
        assertNull(status.businessDate());
        assertEquals("当前采集未完成，请查看来源状态后重试", status.message());
    }

    @Test
    void concurrentRefreshDoesNotStartASecondCycleAndControllerReturnsAccepted() {
        ActiveCurrentCollectionService cycle = mock(ActiveCurrentCollectionService.class);
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

    private static ActiveCurrentCollectionService.Result result() {
        return new ActiveCurrentCollectionService.Result("SUCCEEDED", "2026-08-20", "completed");
    }

    @Test
    void completedCycleReleasesAdmissionAndFatalFailureDoesNotStickRunning() {
        ActiveCurrentCollectionService cycle = mock(ActiveCurrentCollectionService.class);
        when(cycle.runImmediateCycle()).thenThrow(new AssertionError("simulated fatal worker failure")).thenReturn(result());
        CurrentAcquisitionService service = new CurrentAcquisitionService(cycle, CLOCK, Runnable::run);
        org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class, service::trigger);
        assertEquals("FAILED", service.status().state());
        assertEquals("SUCCEEDED", service.trigger().state());
        org.mockito.Mockito.verify(cycle, org.mockito.Mockito.times(2)).runImmediateCycle();
    }
}
