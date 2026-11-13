package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.config.HistoricalBotProperties;
import com.ballknowers.draftsim.config.NflHistoricalProperties;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.historical.HistoricalBoardBuilder.NflRank;
import com.ballknowers.draftsim.historical.HistoricalBoardBuilder.Options;
import com.ballknowers.draftsim.historical.HistoricalBoardBuilder.RankConfig;
import com.ballknowers.draftsim.historical.HistoricalBoardBuilder.RankMode;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Football for the historical draft game (spec 056 T020): a 12-team, 15-round plain snake over QB/RB/WR/TE, ranked
 * by last season's points over replacement, replayed week by week from the nflverse stats.
 *
 * <p>The league shape, scoring, rank constants and the offered season range all come from
 * {@link NflHistoricalProperties}; nothing is declared twice (the first and last offered season used to be in the
 * repository too).
 */
@Component
public class NflHistoricalSport implements HistoricalSport {

    public static final String CODE = "NFL";

    /** The 13 scoring keys in display order: {@link StatOrder}'s football keys. One list, used everywhere. */
    public static final List<String> COUNTING = List.of("pass_yd", "pass_td", "pass_int", "rush_yd", "rush_td", "rec",
            "rec_yd", "rec_td", "fum_lost", "pass_2pt", "rush_2pt", "rec_2pt", "st_td");

    /** Shown as season totals on a card, not per game: too rare per game to read (review F16). */
    public static final Set<String> TOTAL_KEYS = Set.of("fum_lost", "pass_2pt", "rush_2pt", "rec_2pt", "st_td");

    public static final String ATTRIBUTION = "Stats: nflverse (nflverse-data), CC BY 4.0";

    /** The positions the game drafts. Replacement depth is derived for exactly these from the roster. */
    private static final List<String> POSITIONS = List.of("QB", "RB", "WR", "TE");

    private final NflHistoricalProperties props;
    private final HistoricalDataRepository data;
    private final NflHistoricalRepository nfl;

    private final BotTuning botTuning;

    /** The shared engine's bots, untuned: what every caller before spec 062 got. */
    public NflHistoricalSport(NflHistoricalProperties props, HistoricalDataRepository data,
                              NflHistoricalRepository nfl) {
        this(props, data, nfl, BotTuning.NONE);
    }

    @Autowired
    public NflHistoricalSport(NflHistoricalProperties props, HistoricalDataRepository data,
                              NflHistoricalRepository nfl, HistoricalBotProperties bots) {
        this(props, data, nfl, bots.nfl());
    }

    public NflHistoricalSport(NflHistoricalProperties props, HistoricalDataRepository data,
                              NflHistoricalRepository nfl, BotTuning botTuning) {
        this.props = props;
        this.data = data;
        this.nfl = nfl;
        this.botTuning = botTuning;
    }

    @Override public BotTuning botTuning() { return botTuning; }

    @Override public String code() { return CODE; }

    @Override public Sport sport() { return Sport.NFL; }

    @Override public int teams() { return props.teams(); }

    @Override public int rounds() { return props.rounds(); }

    @Override public int reversalRound() { return props.reversalRound(); }

    @Override public List<String> rosterPositions() { return props.roster(); }

    @Override public Map<String, Double> baseScoring() { return props.scoring(); }

    @Override public List<String> countingKeys() { return COUNTING; }

    @Override public int firstOffered() { return props.firstOffered(); }

    @Override public int lastOffered() { return props.lastOffered(); }

    @Override public String label(int seasonStart) { return String.valueOf(seasonStart); }

    @Override
    public List<SeasonRow> offeredSeasons() {
        return data.offeredSeasons(CODE, props.firstOffered(), props.lastOffered());
    }

    @Override public List<SeasonRow> offeredSeasons(int from, int to) { return data.offeredSeasons(CODE, from, to); }

    @Override public Optional<SeasonRow> findSeason(int seasonStart) { return data.findSeason(CODE, seasonStart); }

    /**
     * Per position, how many veterans start across the league: teams x that position's DEDICATED roster slots. The
     * FLEX is ignored on purpose (review F6, a hand-set choice, labelled): counting it would need a guess about which
     * position fills it. With the 12-team roster this is QB 12, RB 24, WR 24, TE 12.
     */
    Map<String, Integer> replacementDepth() {
        Map<String, Integer> depth = new LinkedHashMap<>();
        for (String pos : POSITIONS) {
            int slots = (int) props.roster().stream().filter(pos::equals).count();
            if (slots > 0) depth.put(pos, slots * props.teams());
        }
        return depth;
    }

    @Override
    public List<HistoricalPlayerCard> buildBoard(SeasonRow season, long seed, Map<String, Double> scoring) {
        int s = season.seasonStart();
        List<Map<Long, NflHistoricalRepository.NflSeasonLine>> priors = new ArrayList<>(props.rankLookbackSeasons());
        // S-1 first; an unimported season is just empty (1999 is the prior-only season behind 2000).
        for (int i = 1; i <= props.rankLookbackSeasons(); i++) priors.add(nfl.lines(s - i));
        Options opts = new Options(Sport.NFL, COUNTING, this::label, RankMode.POINTS_OVER_REPLACEMENT,
                nfl.draftPicks(s), new NflRank(replacementDepth(), props.rookiePositionalOffset(),
                props.rookieMaxRound(), TOTAL_KEYS));
        return HistoricalBoardBuilder.build(s, seed, scoring, nfl.candidates(s), priors, nfl.lines(s),
                new RankConfig(props.rankStandardGames(), props.rankMinGames()), opts);
    }

    @Override
    public List<? extends WeekStatLine> playedWeeks(int seasonStart, Collection<Long> playerIds) {
        return nfl.playedWeeks(seasonStart, playerIds);
    }

    /** No dates in the nflverse stats files (R4), so no week starts: the replay and the web show "Week N". */
    @Override public Map<Integer, LocalDate> weekStarts(int seasonStart) { return Map.of(); }

    /**
     * Built from the config it describes (review S3): the team count, rounds, dedicated slots, FLEX and bench all
     * come from {@link NflHistoricalProperties}, so the label cannot drift from the league.
     */
    @Override
    public String settingsSource() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String slot : props.roster()) if (!slot.equals("BN")) counts.merge(slot, 1, Integer::sum);
        List<String> parts = new ArrayList<>();
        counts.forEach((slot, n) -> parts.add(n > 1 ? n + " " + slot : slot));
        long bench = props.roster().stream().filter("BN"::equals).count();
        return "Hand-set " + props.teams() + "-team, " + props.rounds() + "-round roster: " + String.join(", ", parts)
                + (bench > 0 ? " + " + bench + " bench" : "") + " · full PPR";
    }

    // Never "ADP" (FR-009): there is no historical ADP here, and the ranking is not one.
    @Override public String rankingLabel() { return "ranked by last season's points over replacement"; }

    @Override public String attribution() { return ATTRIBUTION; }

    @Override
    public List<String> dataNotes(SeasonRow season) {
        List<String> notes = new ArrayList<>();
        Integer expected = season.expectedGameCount();
        if (expected != null && expected != season.gameCount()) {
            notes.add(season.gameCount() + " of " + expected + " regular-season games are in the data");
        }
        if (season.droppedRows() > 0) {
            notes.add(season.droppedRows() + (season.droppedRows() == 1 ? " source row" : " source rows")
                    + " with no player id (a skill position or scored stats) "
                    + (season.droppedRows() == 1 ? "was" : "were") + " left out");
        }
        // Always (O2): the file's position is the player's current listing, not his position that season.
        notes.add("Positions are each player's current listing, not necessarily his position that season");
        return notes;
    }
}
