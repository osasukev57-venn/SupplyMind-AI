package com.supplymind.scheduling;

import com.supplymind.backfill.BackfillOrchestrator;
import com.supplymind.foundation.model.*;
import com.supplymind.foundation.storage.ConfigActivationStore;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActiveCurrentCollectionServiceTest {
    @Test void failureDoesNotBlockOtherAutomaticTargetsAndManualIsNotFaked() {
        var configs=mock(ConfigActivationStore.class);
        var intake=mock(BackfillOrchestrator.class);
        var config=MonitorSeriesDefaults.initialDay3(OffsetDateTime.parse("2026-08-10T12:00:00+08:00"));
        when(configs.readActiveConfig()).thenReturn(config);
        for(var item:config.items()) if(item.providerType().isExternalHttpProvider()) {
            when(intake.collectCurrent(item.itemId())).thenReturn(new BackfillOrchestrator.CurrentIntakeOutcome(
                    BackfillOrchestrator.CurrentIntakeStatus.SUCCEEDED,item.itemId(),1,List.of()));
        }
        when(intake.collectCurrent(MonitorSeriesDefaults.USD_CNY_ITEM_ID)).thenThrow(new IllegalStateException("secret"));
        var result=new ActiveCurrentCollectionService(configs,intake).runImmediateCycle();
        assertEquals("PARTIAL_SUCCESS",result.state());
        assertFalse(result.message().contains("secret"));
        verify(intake).collectCurrent(MonitorSeriesDefaults.EUR_CNY_ITEM_ID);
        verify(intake).collectCurrent(MonitorSeriesDefaults.ADC12_SMM_ITEM_ID);
        verify(intake,never()).collectCurrent(MonitorSeriesDefaults.AZ91D_SMM_ITEM_ID);
    }
    @Test void cronHasExplicitBusinessZone() throws Exception {
        var annotation=RotationGuardConfiguration.RotationGuardedScheduler.class
                .getMethod("runGuardedCycle").getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        assertEquals("Asia/Shanghai",annotation.zone());
    }
}
