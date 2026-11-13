package com.ballknowers.draftsim.historical;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * What {@code /api/history/games/{id}} returns (contracts/api.md, spec 053). Mirrored field-for-field in
 * {@code web/src/api.ts}.
 *
 * <p><b>While {@code status} is DRAFTING nothing from the drafted season's own statistics is in here</b>
 * (FR-003): cards carry prior-season lines only. {@code season} carries the label and the era disclosure
 * (what counts, with coverage), which describe the rules of the game, not any player's numbers.
 *
 * <p>{@code result} is the stored JSON passed through untouched (it is a {@link HistoricalResult} when parsed),
 * so a reopened game returns exactly what was frozen at completion (FR-013). Null until COMPLETE.
 * {@code available} is empty once the game is no longer DRAFTING. {@code reversalRound} is the game's stored value
 * (0 is a plain snake), so the web can lay out upcoming picks without its own snake arithmetic (spec 063).
 */
public record HistoricalGameState(long id, String sport, String status, SeasonView season, int teams, int rounds,
                                  List<String> rosterPositions, int userSlot, List<Integer> myPicks,
                                  int currentPickNo, Integer onTheClockSlot, boolean isUsersTurn,
                                  List<PickView> picks, List<HistoricalPlayerCard> available, JsonNode result,
                                  int reversalRound) {

    /**
     * @param countedStats the scoring keys this season counts, from the game's own frozen scoring snapshot
     * @param rankingLabel what to call the board order: never "ADP" (FR-009)
     * @param dataNotes    plain-language caveats about the data behind this season (spec 056 FR-014); empty when
     *                     there is nothing to say, never null
     * @param attribution  the data-licence line to show (nflverse, CC BY 4.0 for football); null for basketball
     */
    public record SeasonView(String label, List<String> countedStats, Map<String, String> excludedStats,
                             Map<String, Double> statCoverage, String rankingSource, String rankingLabel,
                             String settingsSource, List<String> dataNotes, String attribution) {
        public SeasonView {
            dataNotes = dataNotes == null ? List.of() : List.copyOf(dataNotes);
        }
    }

    public record PickView(int pickNo, int round, int draftSlot, String seatType, HistoricalPlayerCard player) {}

    /**
     * One row of {@code GET /api/history/games}; {@code finish} is null until COMPLETE.
     * {@code formatVersion} and {@code outcome} (spec 063) are null for an unfinished game and for a game completed
     * before playoffs existed, whose {@code finish} is a standings rank.
     */
    public record GameSummary(long id, String sport, String seasonLabel, String status, int userSlot,
                              Integer finish, Integer formatVersion, String outcome, OffsetDateTime createdAt) {}
}
