package com.ballknowers.draftsim.domain;

import com.ballknowers.draftsim.engine.SimulationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one rookie rule (specs/014 research R4): years_exp is exactly 0. Null is not a rookie,
 * which is what keeps NFL team defenses (null experience) out without a special case.
 */
class PlayerRookieTest {

    private static Player player(Integer yearsExp) {
        return new Player(1, Sport.NFL, "s1", "Someone", List.of(Position.RB), "NYJ", "Active", null, 22, yearsExp);
    }

    @Test
    void zeroYearsIsARookie() {
        assertThat(player(0).isRookie()).isTrue();
    }

    @Test
    void aVeteranIsNot() {
        assertThat(player(1).isRookie()).isFalse();
        assertThat(player(9).isRookie()).isFalse();
    }

    @Test
    void unknownExperienceIsNotARookie() {
        assertThat(player(null).isRookie()).isFalse();
    }

    @Test
    void theWirePlayerCarriesTheSameAnswer() {
        assertThat(SimulationResult.PlayerRef.from(new BoardEntry(player(0), 12.0, 3)).rookie()).isTrue();
        assertThat(SimulationResult.PlayerRef.from(new BoardEntry(player(4), 12.0, 3)).rookie()).isFalse();
        assertThat(SimulationResult.PlayerRef.from(new BoardEntry(player(null), 12.0, 3)).rookie()).isFalse();
    }
}
