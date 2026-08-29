package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.config.ScoringProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final ScoringProperties scoring;

    public HealthController(ScoringProperties scoring) {
        this.scoring = scoring;
    }

    /** Confirms the external weights file actually loaded. */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "up",
                "weightsLoaded", scoring.football() != null,
                "weights", scoring.football() == null ? Map.of() : scoring.football().weights()
        );
    }
}
