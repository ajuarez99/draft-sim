package com.ballknowers.draftsim.historical;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HistoricalRookiesTest {

    private static HistoricalPlayerCard card(long id) {
        return new HistoricalPlayerCard(id, "P" + id, List.of("RB"), null, null, (int) id, (int) id, null);
    }

    private static Boolean rookie(List<HistoricalPlayerCard> marked, long id) {
        return marked.stream().filter(c -> c.id() == id).findFirst().orElseThrow().rookie();
    }

    @Test
    void theBuilderLeavesTheFlagUnknown() {
        assertThat(card(1).rookie()).isNull();
    }

    @Test
    void noEarlierLineIsARookieAndAnEarlierLineIsNot() {
        var marked = HistoricalRookies.mark(List.of(card(1), card(2)), Set.of(1L), Map.of(), 1996);
        assertThat(rookie(marked, 1)).isFalse();
        assertThat(rookie(marked, 2)).isTrue();
    }

    @Test
    void aSeasonWithNobodyToCompareAgainstIsUnknownNotAllRookies() {
        // The first season a source covers: no card has an earlier line, so "first season" says nothing.
        var marked = HistoricalRookies.mark(List.of(card(1), card(2)), Set.of(), Map.of(), 1969);
        assertThat(rookie(marked, 1)).isNull();
        assertThat(rookie(marked, 2)).isNull();
    }

    @Test
    void veteransWhoAreNotOnThisBoardDoNotMakeItComparable() {
        var marked = HistoricalRookies.mark(List.of(card(1)), Set.of(99L), Map.of(), 2017);
        assertThat(rookie(marked, 1)).isNull();
    }

    @Test
    void footballTheDraftYearWinsOverTheFirstLine() {
        // 1: veteran. 2: drafted this season. 3: drafted LAST season, first line this season (sat a year):
        // not a rookie. 4: undrafted, no earlier line: a rookie. 5: drafted this season but somehow has an
        // earlier line: the draft table still wins.
        var marked = HistoricalRookies.mark(List.of(card(1), card(2), card(3), card(4), card(5)),
                Set.of(1L, 5L), Map.of(2L, 2021, 3L, 2020, 5L, 2021), 2021);
        assertThat(rookie(marked, 1)).isFalse();
        assertThat(rookie(marked, 2)).isTrue();
        assertThat(rookie(marked, 3)).isFalse();
        assertThat(rookie(marked, 4)).isTrue();
        assertThat(rookie(marked, 5)).isTrue();
    }

    @Test
    void footballInItsFirstDataSeasonMarksDrafteesAndLeavesTheRestUnknown() {
        var marked = HistoricalRookies.mark(List.of(card(1), card(2)), Set.of(), Map.of(2L, 2000), 2000);
        assertThat(rookie(marked, 1)).isNull();
        assertThat(rookie(marked, 2)).isTrue();
    }

    @Test
    void markingChangesNothingElseOnTheCard() {
        HistoricalPlayerCard before = card(7);
        HistoricalPlayerCard after = HistoricalRookies.mark(List.of(before), Set.of(7L), Map.of(), 2001).getFirst();
        assertThat(after.withRookie(null)).isEqualTo(before);
    }
}
