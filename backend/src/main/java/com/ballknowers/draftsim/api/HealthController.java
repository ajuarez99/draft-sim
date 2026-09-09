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

    /**
     * Confirms the external weights file actually loaded.
     *
     * Reports only whether they loaded, not what they are. This route is
     * deliberately exempt from the API token filter so a platform health check
     * works without holding the secret -- which made it the one endpoint that
     * published the model's tuning to anyone who asked. `weightsLoaded` is the
     * whole diagnostic DEPLOY.md's step 1 actually needs.
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "up",
                "weightsLoaded", scoring.football() != null
        );
    }
}
