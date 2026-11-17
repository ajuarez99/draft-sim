package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.config.HistoricalBotProperties;
import com.ballknowers.draftsim.config.HistoricalSeasonPickProperties;
import com.ballknowers.draftsim.config.HistoricalProperties;
import com.ballknowers.draftsim.config.HistoricalRookieProperties;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.LeagueShape;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.GameLine;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.NamedSeasonLine;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonLine;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Basketball, exactly as spec 053 shipped it (spec 056 T009): the same league shape, scoring, board ranking and
 * data, moved behind {@link HistoricalSport} so the game flow no longer names NBA.
 */
@Component
public class NbaHistoricalSport implements HistoricalSport {

    /** What the screen prints as where the settings come from (FR-002, plan F13). */
    public static final String SETTINGS_SOURCE = "App standard 12-team NBA roster · Legacy Ball Knowers scoring";

    public static final String CODE = "NBA";

    private final HistoricalProperties props;
    private final HistoricalDataRepository data;

    private final BotTuning botTuning;

    private final SeasonSkew seasonSkew;

    /** Null (with {@link #draftPicks}) when rookies are left in the random tail: every caller but the live app. */
    private final HistoricalRookieProperties.Nba rookies;

    private final NbaDraftPicks draftPicks;

    /** The shared engine's bots, untuned: what every caller before spec 062 got. */
    public NbaHistoricalSport(HistoricalProperties props, HistoricalDataRepository data) {
        this(props, data, BotTuning.NONE);
    }

    @Autowired
    public NbaHistoricalSport(HistoricalProperties props, HistoricalDataRepository data,
                              HistoricalBotProperties bots, HistoricalSeasonPickProperties seasonPick,
                              HistoricalRookieProperties rookies) {
        this(props, data, bots.nba(), seasonPick.nba(), rookies.nba(),
                rookies.nba() == null ? null : NbaDraftPicks.fromClasspath());
    }

    /** A flat random-year draw: what every caller before the recent-season skew got. */
    public NbaHistoricalSport(HistoricalProperties props, HistoricalDataRepository data, BotTuning botTuning) {
        this(props, data, botTuning, SeasonSkew.NONE);
    }

    /** Rookies in the random tail: what every caller before the draft-pick placement got. */
    public NbaHistoricalSport(HistoricalProperties props, HistoricalDataRepository data, BotTuning botTuning,
                              SeasonSkew seasonSkew) {
        this(props, data, botTuning, seasonSkew, null, null);
    }

    public NbaHistoricalSport(HistoricalProperties props, HistoricalDataRepository data, BotTuning botTuning,
                              SeasonSkew seasonSkew, HistoricalRookieProperties.Nba rookies,
                              NbaDraftPicks draftPicks) {
        if ((rookies == null) != (draftPicks == null)) {
            throw new IllegalArgumentException("the rookie rule and the draft picks come together or not at all");
        }
        this.props = props;
        this.data = data;
        this.botTuning = botTuning;
        this.seasonSkew = seasonSkew;
        this.rookies = rookies;
        this.draftPicks = draftPicks;
    }

    @Override public BotTuning botTuning() { return botTuning; }

    @Override public SeasonSkew seasonSkew() { return seasonSkew; }

    @Override public String code() { return CODE; }

    @Override public Sport sport() { return Sport.NBA; }

    @Override public int teams() { return props.teams(); }

    @Override public int rounds() { return props.rounds(); }

    @Override public int regularSeasonWeeks() { return props.regularSeasonWeeks(); }
    @Override public int reversalRound() { return props.reversalRound(); }

    @Override public List<String> rosterPositions() { return LeagueShape.NBA_ROSTER; }

    @Override public Map<String, Double> baseScoring() { return props.scoring(); }

    @Override public List<String> countingKeys() { return HistoricalBoardBuilder.COUNTING; }

    @Override public int firstOffered() { return HistoricalDataRepository.NBA_FIRST_OFFERED; }

    @Override public int lastOffered() { return HistoricalDataRepository.NBA_LAST_OFFERED; }

    @Override public String label(int seasonStart) { return HistoricalBoardBuilder.label(seasonStart); }

    @Override public List<SeasonRow> offeredSeasons() { return data.offeredSeasons(CODE); }

    @Override public List<SeasonRow> offeredSeasons(int from, int to) { return data.offeredSeasons(CODE, from, to); }

    @Override public Optional<SeasonRow> findSeason(int seasonStart) { return data.findSeason(CODE, seasonStart); }

    @Override
    public HistoricalBoardBuilder.Board buildBoardRanked(SeasonRow season, long seed, Map<String, Double> scoring) {
        int s = season.seasonStart();
        List<Map<Long, SeasonLine>> priors = new ArrayList<>(props.rankLookbackSeasons());
        for (int i = 1; i <= props.rankLookbackSeasons(); i++) priors.add(lines(s - i));   // S-1 first; an unimported season is just empty
        List<HistoricalBoardBuilder.Candidate> candidates = data.candidates(s);
        java.util.Set<Long> veterans = data.playersWithLineBefore(s);
        HistoricalBoardBuilder.Options opts = rookies == null
                ? HistoricalBoardBuilder.nba()
                : HistoricalBoardBuilder.nba(
                        draftPicks.rookiesOf(s, candidates, veterans, rookies.maxSeasonsSinceDraft()),
                        new HistoricalBoardBuilder.RookieRank(rookies.firstRank(), rookies.rankStep()));
        HistoricalBoardBuilder.Board board = HistoricalBoardBuilder.buildRanked(s, seed, scoring, candidates,
                priors, lines(s),
                new HistoricalBoardBuilder.RankConfig(props.rankStandardGames(), props.rankMinGames()), opts);
        return new HistoricalBoardBuilder.Board(HistoricalRookies.mark(board.cards(), veterans, Map.of(), s),
                board.rankedCount());
    }

    private Map<Long, SeasonLine> lines(int season) {
        Map<Long, SeasonLine> out = new HashMap<>();
        for (NamedSeasonLine l : data.seasonLines(season)) out.put(l.line().playerId(), l.line());
        return out;
    }

    @Override
    public List<GameLine> playedWeeks(int seasonStart, Collection<Long> playerIds) {
        return data.playedGames(seasonStart, playerIds);
    }

    @Override public Map<Integer, LocalDate> weekStarts(int seasonStart) { return data.weekStarts(seasonStart); }

    @Override public String settingsSource() { return SETTINGS_SOURCE; }

    /** Checked against {@link SeasonReplay#scorePlayerWeeks}: a week's score is the MAX over that week's games. */
    @Override public String pointsNote() {
        return "Each week only a player's best single game counts, and the weeks add up. That is not the season total"
                + " you would see on a fantasy site.";
    }

    // Never "ADP" (FR-009): no trustworthy historical ADP exists (research R4).
    @Override public String rankingLabel() {
        return rookies == null ? "ranked by last season's points"
                : "ranked by last season's points, rookies by NBA draft pick";
    }

    @Override public String attribution() { return null; }

    @Override public List<String> dataNotes(SeasonRow season) { return List.of(); }
}
