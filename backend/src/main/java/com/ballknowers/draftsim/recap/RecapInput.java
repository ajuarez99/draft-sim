package com.ballknowers.draftsim.recap;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * What the model is shown: one finished week, reduced and with its derived numbers computed here
 * (margins, week high/low, score ranks) so the model never has to (FR-002). Deliberately carries no
 * username, avatar id, roster id, {@code isMe} or performer pro team (R6, F7). Null fields are
 * left out of the JSON: absent means "does not apply to this sport", not "none".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecapInput(
        int season,
        int week,
        String sport,
        String basis,
        List<Matchup> matchups,
        Extreme weekHigh,
        Extreme weekLow,
        List<Performer> topPerformers,
        List<Night> bestNights,
        List<WeekTotal> bestWeek,
        List<Award> awards,
        List<Omitted> awardsOmitted,
        List<Unavailable> sectionsUnavailable) {

    /** {@code winner} is null on a tie. {@code margin} is the absolute difference, 2 dp. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Matchup(Side home, Side away, BigDecimal margin, String winner) {}

    /** {@code scoreRank} is 1 for the week's highest score (competition ranking: ties share the better rank). */
    public record Side(String teamName, BigDecimal points, String record, int scoreRank) {}

    public record Extreme(String teamName, BigDecimal points) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Performer(String playerName, String position, String teamName, BigDecimal points,
                            String opponent, Boolean isAway) {}

    /** {@code date} is an ISO local date, an atomic token to the grounding check (R5). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Night(String playerName, String position, String teamName, BigDecimal points, String date,
                        String opponent, Boolean isAway) {}

    public record WeekTotal(String playerName, String position, String teamName, BigDecimal totalPoints,
                            int gamesPlayed) {}

    public record Award(String kind, String teamName, String detail) {}

    public record Omitted(String kind, String reason) {}

    public record Unavailable(String section, String reason) {}
}
