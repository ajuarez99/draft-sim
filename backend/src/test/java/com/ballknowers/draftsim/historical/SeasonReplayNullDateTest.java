package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.historical.SeasonReplay.Pick;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ballknowers.draftsim.historical.HistoricalTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 056 T013 / review F4: football lines carry no game date, and 053's replay called {@code l.date().toString()}
 * on every starter, which would throw at the end of every football draft. A week line with a null date must replay,
 * score, and report the starter's date as null.
 */
class SeasonReplayNullDateTest {

    /** A week line the way football makes one: no date, one stat. */
    private record UndatedWeek(long playerId, int week, double pts) implements WeekStatLine {
        @Override public LocalDate gameDate() { return null; }

        @Override public Double stat(String key) { return key.equals("pts") ? pts : null; }
    }

    @Test
    void anUndatedStarterReplaysWithANullDateAndTheRightPoints() {
        List<Pick> picks = new ArrayList<>();
        List<UndatedWeek> games = new ArrayList<>();
        Map<Long, HistoricalPlayerCard> cards = new LinkedHashMap<>();
        String[] pos = {"PG", "SG", "SF", "PF", "C"};
        for (long id = 1; id <= 28; id++) {   // 2 teams x 14 rounds
            cards.put(id, card(id, pos[(int) (id % 5)]));
            picks.add(new Pick((int) id, 1 + (int) ((id - 1) % 2), id));
            games.add(new UndatedWeek(id, 1, id));
        }
        for (long id = 29; id <= 60; id++) {   // undrafted filler: the hindsight comparison needs a pool
            cards.put(id, card(id, pos[(int) (id % 5)]));
            games.add(new UndatedWeek(id, 1, id));
        }
        Map<String, Double> scoring = new LinkedHashMap<>();
        scoring.put("pts", 1.0);

        HistoricalResult r = REPLAY.replay(new SeasonReplay.Input(settings(2), rules(), scoring, 1, 7L, 1, picks,
                cards, games, Map.of()));

        var starters = r.lineups().get("1").get(0).starters();
        assertFalse(starters.isEmpty());
        for (var s : starters) assertNull(s.gameDate(), "no date in the source means no date, not a crash");
        assertNull(r.weeks().get(0).startDate(), "and no week start either");
        assertTrue(starters.stream().mapToDouble(HistoricalResult.Starter::points).sum() > 0,
                "the undated lines still scored");
    }
}
