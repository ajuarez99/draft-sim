package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.LeagueMatchupRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * specs/008-season-superlatives T020: preference-ordering tests for the
 * package-private {@link SeasonSuperlativesService#closeGames} counter,
 * against an in-memory list of {@link LeagueMatchupRepository.PairedGame} --
 * no Postgres involved (AGENTS.md recurring bug #1: a scoring/ranking change
 * needs a test that asserts preference ordering, not just structure).
 */
class SeasonSuperlativesCloseGamesTest {

    private static final double MARGIN = 10.0;

    private static LeagueMatchupRepository.PairedGame game(int week, int aRoster, double aPts, int bRoster, double bPts) {
        return new LeagueMatchupRepository.PairedGame(2026, week,
                aRoster, null, "A" + aRoster, null, BigDecimal.valueOf(aPts),
                bRoster, null, "A" + bRoster, null, BigDecimal.valueOf(bPts));
    }

    // (a) team C (roster 3) wins three close games, team D (roster 4) wins two.
    @Test
    void closeWinsNamesTheTeamWithMoreCloseWinsNotFewer() {
        List<LeagueMatchupRepository.PairedGame> games = List.of(
                game(1, 3, 105, 9, 100),   // C wins by 5
                game(2, 3, 110, 8, 103),   // C wins by 7
                game(3, 3, 120, 7, 112),   // C wins by 8
                game(4, 4, 100, 6, 95),    // D wins by 5
                game(5, 4, 90, 5, 82));    // D wins by 8

        Map<Integer, List<SeasonSuperlativesService.GameDetail>> wins =
                SeasonSuperlativesService.closeGames(games, MARGIN, true);

        assertThat(wins.get(3)).hasSize(3);
        assertThat(wins.get(4)).hasSize(2);
    }

    // (b) the mirror case for CLOSE_LOSSES.
    @Test
    void closeLossesNamesTheTeamWithMoreCloseLossesNotFewer() {
        List<LeagueMatchupRepository.PairedGame> games = List.of(
                game(1, 9, 100, 3, 105),   // roster 9 loses by 5
                game(2, 8, 103, 3, 110),   // roster 8 loses by 7
                game(3, 7, 112, 3, 120),   // roster 7 loses by 8
                game(4, 6, 95, 4, 100),    // roster 6 loses by 5
                game(5, 5, 82, 4, 90));    // roster 5 loses by 8

        Map<Integer, List<SeasonSuperlativesService.GameDetail>> losses =
                SeasonSuperlativesService.closeGames(games, MARGIN, false);

        // Each losing roster above appears once; none of them ties for "most".
        assertThat(losses.get(9)).hasSize(1);
        assertThat(losses.get(6)).hasSize(1);
    }

    // (c) C (roster 3) and E (roster 5) tied at 3 close wins each: both are holders.
    @Test
    void tiedCloseWinCountsBothBecomeHolders() {
        List<LeagueMatchupRepository.PairedGame> games = List.of(
                game(1, 3, 105, 9, 100), game(2, 3, 110, 8, 103), game(3, 3, 120, 7, 112),
                game(4, 5, 105, 6, 100), game(5, 5, 110, 4, 103), game(6, 5, 120, 2, 112));

        Map<Integer, List<SeasonSuperlativesService.GameDetail>> wins =
                SeasonSuperlativesService.closeGames(games, MARGIN, true);

        int max = wins.values().stream().mapToInt(List::size).max().orElse(0);
        List<Integer> topRosters = wins.entrySet().stream()
                .filter(e -> e.getValue().size() == max)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();

        assertThat(max).isEqualTo(3);
        assertThat(topRosters).containsExactly(3, 5);
    }

    // (d) no game under the margin: holders empty (nothing in the map at all).
    @Test
    void noCloseGamesLeavesTheMapEmpty() {
        List<LeagueMatchupRepository.PairedGame> games = List.of(
                game(1, 3, 130, 9, 90),    // margin 40, not close
                game(2, 4, 115, 8, 80));   // margin 35, not close

        assertThat(SeasonSuperlativesService.closeGames(games, MARGIN, true)).isEmpty();
        assertThat(SeasonSuperlativesService.closeGames(games, MARGIN, false)).isEmpty();
    }

    // (e) a margin exactly equal to 10.0 is NOT close -- strict "<", per FR-011's "under".
    @Test
    void aMarginExactlyEqualToTheThresholdIsNotClose() {
        List<LeagueMatchupRepository.PairedGame> games = List.of(
                game(1, 3, 110, 9, 100)); // margin exactly 10.0

        assertThat(SeasonSuperlativesService.closeGames(games, MARGIN, true)).isEmpty();
        assertThat(SeasonSuperlativesService.closeGames(games, MARGIN, false)).isEmpty();
    }

    // (f) detail lists every qualifying game, with week, opponent and margin.
    @Test
    void detailListsEveryQualifyingGameWithWeekOpponentAndMargin() {
        List<LeagueMatchupRepository.PairedGame> games = List.of(
                game(1, 3, 105, 9, 100),   // C wins by 5
                game(2, 3, 110, 8, 103));  // C wins by 7

        Map<Integer, List<SeasonSuperlativesService.GameDetail>> wins =
                SeasonSuperlativesService.closeGames(games, MARGIN, true);

        List<SeasonSuperlativesService.GameDetail> detail = wins.get(3);
        assertThat(detail).hasSize(2);
        assertThat(detail.get(0).week()).isEqualTo(1);
        assertThat(detail.get(0).opponentRosterId()).isEqualTo(9);
        assertThat(detail.get(0).margin()).isEqualTo(5.0);
        assertThat(detail.get(1).week()).isEqualTo(2);
        assertThat(detail.get(1).opponentRosterId()).isEqualTo(8);
        assertThat(detail.get(1).margin()).isEqualTo(7.0);
    }

    // T023: weeks with scores but no pairings are excluded from the four
    // pairing-based kinds. The service layer builds Coverage from the set
    // difference between scoredWeeks and the weeks actually present in
    // `games` -- exercised here by simulating that difference directly,
    // since it needs no Postgres either.
    @Test
    void weeksWithNoPairingsAreExcludedFromCloseGameCounts() {
        // Week 3 has no paired games at all (simulating "scores exist, no
        // pairing stored") -- the pure counter simply never sees it, since
        // the caller filters `games` down to weeks it actually has pairings
        // for before calling closeGames.
        List<LeagueMatchupRepository.PairedGame> games = List.of(
                game(1, 3, 105, 9, 100)); // week 3 is absent entirely

        Map<Integer, List<SeasonSuperlativesService.GameDetail>> wins =
                SeasonSuperlativesService.closeGames(games, MARGIN, true);

        assertThat(wins.get(3)).hasSize(1);
        assertThat(wins.get(3).get(0).week()).isEqualTo(1);
    }
}
