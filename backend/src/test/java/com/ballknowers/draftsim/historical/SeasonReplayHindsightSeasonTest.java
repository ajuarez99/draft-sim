package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.historical.HistoricalDataRepository.GameLine;
import com.ballknowers.draftsim.historical.HistoricalResult.HindsightSeason;
import com.ballknowers.draftsim.historical.HistoricalResult.Standing;
import com.ballknowers.draftsim.historical.SeasonReplay.Pick;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntBinaryOperator;

import static com.ballknowers.draftsim.historical.HistoricalTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The hindsight roster's own season: the same schedule replayed with that roster in the user's seat. Twelve teams,
 * one pick each (slot s takes player s at pick s), every score hand-set, so who wins is known.
 */
class SeasonReplayHindsightSeasonTest {

    private static final int TEAMS = 12;
    private static final int REGULAR = 19;
    private static final int SEASON = 25;
    private static final long STAR = 99L;

    /** @param score (playerId, week) to points; {@code starPoints} adds an undrafted player 99 scoring that weekly */
    private static HistoricalResult replay(int userSlot, IntBinaryOperator score, Integer starPoints) {
        List<Pick> picks = new ArrayList<>();
        Map<Long, HistoricalPlayerCard> cards = new LinkedHashMap<>();
        List<GameLine> games = new ArrayList<>();
        for (int s = 1; s <= TEAMS; s++) {
            picks.add(new Pick(s, s, s));
            cards.put((long) s, card(s, "PG"));
            for (int w = 1; w <= SEASON; w++) games.add(game(s, s * 1000L + w, w, 0, score.applyAsInt(s, w), null));
        }
        if (starPoints != null) {
            cards.put(STAR, card(STAR, "PG"));
            for (int w = 1; w <= SEASON; w++) games.add(game(STAR, STAR * 1000L + w, w, 0, starPoints, null));
        }
        Map<Integer, LocalDate> starts = new LinkedHashMap<>();
        for (int w = 1; w <= SEASON; w++) starts.put(w, DAY.plusDays(7L * (w - 1)));
        Map<String, Double> scoring = new LinkedHashMap<>();
        scoring.put("pts", 1.0);
        return REPLAY.replay(new SeasonReplay.Input(settings(TEAMS), rules(), scoring, SEASON, REGULAR, 31L, userSlot,
                picks, cards, games, starts));
    }

    private static final IntBinaryOperator BY_SLOT = (player, week) -> 200 - 10 * player;

    @Test
    void aHindsightRosterThatOutscoresEveryoneEveryWeekGoesUnbeatenAndWinsTheTitle() {
        // The direction: the user (slot 12, the worst team) really missed the playoffs. With the undrafted star in
        // their seat they win every week. A sign error anywhere would leave the hindsight team last, not first.
        HistoricalResult r = replay(12, BY_SLOT, 1000);
        assertEquals(STAR, r.hindsight().picks().get(0).player().id());
        assertEquals(HistoricalResult.MISSED_PLAYOFFS, r.userOutcome());

        HindsightSeason h = r.hindsightSeason();
        assertEquals(REGULAR, h.wins());
        assertEquals(0, h.losses());
        assertEquals(0, h.ties());
        assertEquals(1, h.regularSeasonRank());
        assertEquals(1, h.finish());
        assertEquals(HistoricalResult.CHAMPION, h.outcome());
        assertEquals(1000.0 * REGULAR, h.pointsFor(), 1e-9, "regular-season points, like the standings");
        assertEquals(0, h.takenFromBots(), "nobody drafted the star");
    }

    @Test
    void whenHindsightCanOnlyTakeTheUsersOwnPlayerItsSeasonIsTheUsersSeason() {
        // The last pick of a one-round draft with nobody undrafted: the only player left is the one the user took.
        HistoricalResult r = replay(12, BY_SLOT, null);
        assertEquals(12L, r.hindsight().picks().get(0).player().id());
        Standing mine = r.standings().stream().filter(s -> s.slot() == 12).findFirst().orElseThrow();
        HindsightSeason h = r.hindsightSeason();
        assertEquals(mine.wins(), h.wins());
        assertEquals(mine.losses(), h.losses());
        assertEquals(mine.ties(), h.ties());
        assertEquals(mine.pointsFor(), h.pointsFor(), 1e-9);
        assertEquals(mine.place(), h.regularSeasonRank());
        assertEquals(r.userFinish(), h.finish());
        assertEquals(r.userOutcome(), h.outcome());
        assertNotEquals(HistoricalResult.CHAMPION, h.outcome(), "the worst team does not win by being relabelled");
    }

    @Test
    void aPlayerABotTookLaterComesOffThatBotsRosterSoNobodyScoresForTwoTeams() {
        // Player 9 (taken at pick 9, after the user's pick 5) outscores everyone. Hindsight takes him at pick 5.
        // Strict: team 9 loses him and gets the one free player (player 5, whom the user really took), so the two
        // teams never draw level. If team 9 kept him they would tie every time they met.
        IntBinaryOperator score = (player, week) -> player == 9 ? 500 : 200 - 10 * player;
        HistoricalResult r = replay(5, score, null);
        assertEquals(9L, r.hindsight().picks().get(0).player().id());
        HindsightSeason h = r.hindsightSeason();
        assertEquals(1, h.takenFromBots());
        assertEquals(REGULAR, h.wins(), "500 a week against nobody above 190");
        assertEquals(0, h.ties(), "team 9 no longer has player 9");
        assertEquals(0, h.losses());
        assertEquals(HistoricalResult.CHAMPION, h.outcome());
        Standing nine = r.standings().stream().filter(s -> s.slot() == 9).findFirst().orElseThrow();
        assertEquals(REGULAR, nine.wins(), "the real standings are untouched: team 9 still went unbeaten there");
    }
}
