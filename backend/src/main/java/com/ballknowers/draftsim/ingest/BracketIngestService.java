package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.store.LeagueBracketRepository;
import com.ballknowers.draftsim.store.LeagueLosersBracketRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One season's Sleeper brackets into {@code league_bracket_match} (winners, specs/034-rivalry-schedule-grid T004,
 * contracts C2/C3) and {@code league_losers_bracket_match} (losers, specs/047-hall-of-fame T007). The single
 * method both the chain walk and the bracket-only backfill endpoint call, so there is one write path rather
 * than two.
 *
 * <p>Calls {@code winners_bracket} and {@code losers_bracket} only. It never touches {@code /matchups},
 * standings or points, which is what makes it safe for finished NBA seasons whose live {@code /matchups} is
 * degraded (research R10, memory {@code project_sleeper_nba_2025_matchup_drift}).
 *
 * <p><b>A bracket fetch failure propagates.</b> Inside {@code ingestChain} that fails the chain like any
 * other ingest error, so a season never freezes as {@code loaded_complete} without a bracket (plan.md F6).
 *
 * <p>Each bracket has its own "fully played, leave it alone" skip and its own "empty answer never deletes"
 * rule, so a season whose winners bracket is stored but whose losers bracket isn't (every season stored
 * before V40) still gets exactly the missing fetch.
 */
@Service
public class BracketIngestService {

    private static final Logger log = LoggerFactory.getLogger(BracketIngestService.class);

    private final SleeperClient sleeper;
    private final LeagueBracketRepository brackets;
    private final LeagueLosersBracketRepository losersBrackets;
    private final LeagueRepository leagues;

    public BracketIngestService(SleeperClient sleeper, LeagueBracketRepository brackets,
                                LeagueLosersBracketRepository losersBrackets, LeagueRepository leagues) {
        this.sleeper = sleeper;
        this.brackets = brackets;
        this.losersBrackets = losersBrackets;
        this.leagues = leagues;
    }

    /**
     * @param matches       stored winners-bracket rows after the call
     * @param losersMatches stored losers-bracket rows after the call
     * @param skipped       true when both brackets were fully played and left alone because {@code force} was
     *                      not set
     */
    public record Result(int matches, int losersMatches, boolean skipped) {}

    /**
     * @param force explicit, never defaulted: a rule, not a value. {@code false} leaves a fully-played
     *              stored bracket alone (F5); {@code true} refetches it.
     */
    public Result ingestSeason(long leagueId, String sleeperLeagueId, boolean force) {
        boolean winnersSkipped = !force && brackets.isComplete(leagueId);
        if (!winnersSkipped) {
            List<Map<String, Object>> raw = sleeper.winnersBracket(sleeperLeagueId);
            // A null body is "no data", not an NPE (F5); an empty list is likewise not a deletion.
            if (raw == null || raw.isEmpty()) {
                log.info("winners bracket for {}: Sleeper returned no rows; stored rows left as they were", sleeperLeagueId);
            } else {
                List<LeagueBracketRepository.Match> rows = new ArrayList<>();
                for (Map<String, Object> m : raw) {
                    Integer matchId = intOrNull(m.get("m"));
                    Integer round = intOrNull(m.get("r"));
                    if (matchId == null || round == null) continue;
                    rows.add(new LeagueBracketRepository.Match(leagueId, matchId, round,
                            intOrNull(m.get("t1")), intOrNull(m.get("t2")),
                            intOrNull(m.get("w")), intOrNull(m.get("l")), intOrNull(m.get("p"))));
                }
                brackets.replaceForLeague(leagueId, rows);
            }
        }
        boolean losersSkipped = ingestLosers(leagueId, sleeperLeagueId, force);
        return new Result(brackets.count(leagueId), losersBrackets.count(leagueId), winnersSkipped && losersSkipped);
    }

    /** The losers half of {@link #ingestSeason}; returns true when a fully-played stored bracket was left alone. */
    private boolean ingestLosers(long leagueId, String sleeperLeagueId, boolean force) {
        if (!force && losersBrackets.isComplete(leagueId)) return true;
        List<Map<String, Object>> raw = sleeper.losersBracket(sleeperLeagueId);
        if (raw == null || raw.isEmpty()) {
            log.info("losers bracket for {}: Sleeper returned no rows; stored rows left as they were", sleeperLeagueId);
            return false;
        }
        List<LeagueLosersBracketRepository.Match> rows = new ArrayList<>();
        for (Map<String, Object> m : raw) {
            Integer matchId = intOrNull(m.get("m"));
            Integer round = intOrNull(m.get("r"));
            if (matchId == null || round == null) continue;
            rows.add(new LeagueLosersBracketRepository.Match(leagueId, matchId, round,
                    intOrNull(m.get("t1")), intOrNull(m.get("t2")),
                    intOrNull(m.get("w")), intOrNull(m.get("l")), intOrNull(m.get("p"))));
        }
        losersBrackets.replaceForLeague(leagueId, rows);
        return false;
    }

    /** One season of the {@link #backfillChain} answer (contracts C2). */
    public record SeasonResult(int season, String sleeperLeagueId, int matches, boolean skipped, int losersMatches) {}

    /**
     * Contracts C2: walk the STORED chain ({@link LeagueRepository#chainBySleeperId}, entirely local) and
     * fetch only the two brackets per season -- never {@code /matchups} (research R10). Empty when
     * the league isn't stored. Newest season first, like the chain.
     */
    public List<SeasonResult> backfillChain(String sleeperLeagueId, boolean force) {
        List<SeasonResult> out = new ArrayList<>();
        for (LeagueRepository.LeagueRow row : leagues.chainBySleeperId(sleeperLeagueId)) {
            Result r = ingestSeason(row.id(), row.sleeperId(), force);
            out.add(new SeasonResult(row.season(), row.sleeperId(), r.matches(), r.skipped(), r.losersMatches()));
        }
        return out;
    }

    /**
     * The skipped-season path of the chain walk (plan.md F6): a finished season's bracket is fetched when it
     * is not yet COMPLETE (no rows, or any row without a winner), not merely when it has no rows -- the last
     * non-skipped pass can run while Sleeper's league status already reads complete but the final's winner
     * isn't written yet, and that bracket would otherwise freeze unfinished (code-review B1). Once every row
     * has a winner it is a no-op. Spec 047: the losers bracket (absent for every season stored before V40) is
     * checked the same way, so a finished season missing either bracket gets exactly the missing fetch.
     *
     * <p>No call at all for a league without playoffs ({@code playoff_week_start < 2}, B7). Residual cost,
     * stated plainly: a finished season that HAS playoffs but whose bracket Sleeper answers with {@code []}
     * is asked again on every pass (one {@code winners_bracket} call, no state kept to remember the empty
     * answer). Cheap, and it self-heals if Sleeper later serves the bracket. The same holds for the losers
     * bracket (spec 047 code review N2): a finished season whose {@code losers_bracket} Sleeper answers with
     * {@code []}, or whose stored losers bracket keeps an undecided match (abandoned consolation games), makes
     * one {@code losers_bracket} call on every pass. Deliberately not remembered; no behaviour change.
     */
    public void ingestIfMissing(long leagueId, String sleeperLeagueId) {
        if (brackets.isComplete(leagueId) && losersBrackets.isComplete(leagueId)) return;
        var fmt = leagues.playoffFormat(leagueId);
        if (fmt.isPresent() && fmt.get().playoffWeekStart() < 2) return;
        ingestSeason(leagueId, sleeperLeagueId, false);
    }

    /** Sleeper sends ints, but a slot pointing at an unplayed match carries a map ({@code t1_from}) instead. */
    private static Integer intOrNull(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
