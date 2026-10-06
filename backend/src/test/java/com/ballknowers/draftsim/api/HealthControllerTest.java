package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.config.DraftGradeProperties;
import com.ballknowers.draftsim.config.GradeProperties;
import com.ballknowers.draftsim.config.PlayerTrendsProperties;
import com.ballknowers.draftsim.config.ScoringProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** specs/018-draft-grades T009: the health payload reports draftGradesLoaded. */
class HealthControllerTest {

    @Test
    void reportsDraftGradesLoadedBothWays() {
        ScoringProperties scoring = new ScoringProperties(null, null);
        Map<String, Object> off = new HealthController(scoring, new GradeProperties(List.of()),
                new DraftGradeProperties(null), new PlayerTrendsProperties(null, null, null, null, null, null, null, null)).health();
        assertEquals(false, off.get("draftGradesLoaded"));
        assertEquals(false, off.get("gradesLoaded"));
        assertEquals(false, off.get("playerTrendsLoaded"));

        Map<String, Object> on = new HealthController(scoring, new GradeProperties(List.of()),
                new DraftGradeProperties(3), new PlayerTrendsProperties(6, 5, 5, 3, 14, 10, 20, 0.9)).health();
        assertEquals(true, on.get("draftGradesLoaded"));
        assertEquals(true, on.get("playerTrendsLoaded"));
    }
}
