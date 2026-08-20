package com.supplymind.scheduling.api;

import com.supplymind.scheduling.CurrentAcquisitionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Observable current-data boundary. It exposes no source payload, filesystem path, credential
 * or exception detail; the frontend receives only state, business date and controlled copy.
 */
@RestController
@RequestMapping("/api/acquisition/current")
public final class CurrentAcquisitionController {

    private final CurrentAcquisitionService acquisition;

    public CurrentAcquisitionController(CurrentAcquisitionService acquisition) {
        this.acquisition = acquisition;
    }

    @GetMapping
    public CurrentAcquisitionService.Status status() {
        return acquisition.status();
    }

    @PostMapping("/refresh")
    public ResponseEntity<CurrentAcquisitionService.Status> refresh() {
        return ResponseEntity.accepted().body(acquisition.trigger());
    }
}
