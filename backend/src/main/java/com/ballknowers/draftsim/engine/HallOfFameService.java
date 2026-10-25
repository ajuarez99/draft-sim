package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.RivalryService.ChainGames;
import com.ballknowers.draftsim.engine.RivalryService.Final;
import com.ballknowers.draftsim.engine.RivalryService.PairCalc;
import com.ballknowers.draftsim.engine.RivalryService.PairKey;
import com.ballknowers.draftsim.config.RivalryProperties;
import com.ballknowers.draftsim.store.LeagueBracketRepository;
import com.ballknowers.draftsim.store.LeagueLosersBracketRepository;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository.StandingRow;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The hall of fame (specs/047-hall-of-fame): the trophy case of a league chain, and each manager's nemesis and
 * victim per chain. Records only; no {@code Map.of} anywhere on the response path, because a runner-up, a last
 * place and a manager id are all legitimately null (AGENTS.md hard rule).
 *
 * <p><b>One implementation per rule.</b> The champion is {@link ChampionRule}, the same call History's 🏆 makes.
 * The "final disputed" flag reads the same {@code Final.scoresAgreeWithWinner} the Rivalries page does, from
 * {@link RivalryService#pairCalc}. Last place is {@link LastPlaceRule}.
 */
@Service
public class HallOfFameService {

    // ---------------------------------------------------------------- wire records (contracts, data-model amended)

    /**
     * Who a cell names. {@code managerId} is null for a roster with no manager (an orphan roster; plan A14),
     * and such a name has no link. {@code name} is the manager's display name, {@code teamName} that season's
     * Sleeper team name (null when none).
     */
    public record ManagerRef(Long managerId, String name, String teamName, String avatarId, int rosterId) {}

    /** A {@link ManagerRef} plus the regular-season {@code points_for} that made them the leader. */
    public record PointsLeader(Long managerId, String name, String teamName, String avatarId, int rosterId,
                               double pointsFor) {}

    /**
     * One completed season. {@code runnerUpReason}: NO_BRACKET | NO_LOSER | SOURCES_DISAGREE | null, non-null
     * exactly when {@code runnerUp} is null and a champion exists. {@code lastPlaceReason}: a
     * {@link LastPlaceRule} reason, non-null exactly when {@code lastPlace} is null; {@code lastPlaceBracket}
     * (TOILET_BOWL | CONSOLATION | null) says how it was read.
     */
    public record TrophySeason(int season, String sleeperLeagueId, ManagerRef champion, ManagerRef runnerUp,
                               String runnerUpReason, boolean finalDisputed, List<PointsLeader> pointsLeaders,
                               ManagerRef lastPlace, String lastPlaceReason, String lastPlaceBracket) {}

    /** {@code status}: OK | NO_COMPLETED_SEASON. Seasons are newest first. */
    public record TrophyCase(String sport, String status, List<TrophySeason> seasons) {}

    /**
     * One opponent, from the manager's own side: {@code wins}/{@code losses}/{@code ties} are the manager's.
     * {@code former}: the opponent has no roster in the chain's newest season the manager played.
     */
    public record RivalEntry(ManagerRef opponent, int meetings, int wins, int losses, int ties, boolean former) {}

    /**
     * One league chain (never merged across chains or sports). {@code status}: OK | NONE_QUALIFY, the latter when
     * no opponent has met the manager {@code minMeetings} times. {@code nemesis}/{@code victim} can each be empty
     * under OK (a qualifying opponent exists but none with a losing / winning record). {@code minMeetings} is
     * null only when the rivalry settings didn't load. {@code headSleeperLeagueId} is the chain's newest season
     * the manager played in and the caller may see.
     */
    public record ChainRivals(String sport, String leagueName, String headSleeperLeagueId, String status,
                              Integer minMeetings, List<RivalEntry> nemesis, List<RivalEntry> victim) {}

    public record ManagerRivals(long managerId, List<ChainRivals> chains) {}

    public static final String NO_BRACKET = "NO_BRACKET";
    public static final String NO_LOSER = "NO_LOSER";
    public static final String SOURCES_DISAGREE = "SOURCES_DISAGREE";

    private final LeagueRepository leagues;
    private final RosterSeasonRepository rosterSeasons;
    private final LeagueBracketRepository brackets;
    private final LeagueLosersBracketRepository losersBrackets;
    private final LeagueMemberRepository members;
    private final RosterWeekPointsRepository weekPoints;
    private final com.ballknowers.draftsim.store.LeagueMatchupRepository fixtures;
    private final RivalryService rivalries;
    private final RivalryProperties props;

    public HallOfFameService(LeagueRepository leagues, RosterSeasonRepository rosterSeasons,
                             LeagueBracketRepository brackets, LeagueLosersBracketRepository losersBrackets,
                             LeagueMemberRepository members, RosterWeekPointsRepository weekPoints,
                             com.ballknowers.draftsim.store.LeagueMatchupRepository fixtures,
                             RivalryService rivalries, RivalryProperties props) {
        this.props = props;
        this.leagues = leagues;
        this.rosterSeasons = rosterSeasons;
        this.brackets = brackets;
        this.losersBrackets = losersBrackets;
        this.members = members;
        this.weekPoints = weekPoints;
        this.fixtures = fixtures;
        this.rivalries = rivalries;
    }

    // ---------------------------------------------------------------- trophy case

    /**
     * The chain from {@code sleeperLeagueId} backward (plan A5: never forward), one row per COMPLETE season,
     * newest first. Empty when the league isn't stored.
     */
    public Optional<TrophyCase> trophyCase(String sleeperLeagueId) {
        Optional<LeagueRow> found = leagues.bySleeperId(sleeperLeagueId);
        if (found.isEmpty()) return Optional.empty();
        List<LeagueRow> chain = leagues.chainBySleeperId(sleeperLeagueId);
        if (chain.isEmpty()) chain = List.of(found.get());

        List<LeagueRow> complete = chain.stream().filter(LeagueRow::complete).toList();
        List<Long> completeIds = complete.stream().map(LeagueRow::id).toList();
        Map<Long, List<LeagueBracketRepository.Match>> winners = group(brackets.forLeagues(completeIds),
                LeagueBracketRepository.Match::leagueId);
        Map<Long, List<LeagueLosersBracketRepository.Match>> losers = group(losersBrackets.forLeagues(completeIds),
                LeagueLosersBracketRepository.Match::leagueId);
        // The shared game list is only needed to read "final disputed", and only when a final is stored.
        ChainGames chainGames = null;
        if (!complete.isEmpty() && winners.values().stream().anyMatch(HallOfFameService::hasFinal)) {
            chainGames = rivalries.chainGames(chain);
        }

        List<TrophySeason> seasons = new ArrayList<>();
        for (LeagueRow league : complete) {
            seasons.add(season(league, winners.getOrDefault(league.id(), List.of()),
                    losers.getOrDefault(league.id(), List.of()), chainGames));
        }
        return Optional.of(new TrophyCase(found.get().sport().code(),
                seasons.isEmpty() ? "NO_COMPLETED_SEASON" : "OK", seasons));
    }

    private static boolean hasFinal(List<LeagueBracketRepository.Match> bracket) {
        return bracket.stream().anyMatch(m -> m.placement() != null && m.placement() == 1);
    }

    private TrophySeason season(LeagueRow league, List<LeagueBracketRepository.Match> winnersBracket,
                                List<LeagueLosersBracketRepository.Match> losersBracket, ChainGames chainGames) {
        List<StandingRow> standings = rosterSeasons.forLeague(league.id());
        Map<Integer, StandingRow> byRoster = new HashMap<>();
        for (StandingRow s : standings) byRoster.putIfAbsent(s.rosterId(), s);
        Map<Long, String> teamNames = members.teamNamesByManager(league.id());

        // champion: the one shared rule
        StandingRow champRow = null;
        for (StandingRow s : standings) {
            if (ChampionRule.isChampion(s.finalPlacement(), league.complete())) {
                champRow = s;
                break;
            }
        }
        ManagerRef champion = champRow == null ? null : ref(champRow, teamNames);

        // runner-up: the loser of the bracket's placement-1 match, never promoted past a disagreement
        ManagerRef runnerUp = null;
        String runnerUpReason = null;
        boolean finalDisputed = false;
        if (champRow != null) {
            LeagueBracketRepository.Match fin = winnersBracket.stream()
                    .filter(m -> m.placement() != null && m.placement() == 1).findFirst().orElse(null);
            if (fin == null) {
                runnerUpReason = NO_BRACKET;
            } else if (fin.winnerRosterId() == null || fin.loserRosterId() == null) {
                runnerUpReason = NO_LOSER;
            } else if (fin.winnerRosterId() != champRow.rosterId()) {
                runnerUpReason = SOURCES_DISAGREE;
            } else {
                StandingRow loser = byRoster.get(fin.loserRosterId());
                if (loser == null) {
                    runnerUpReason = NO_LOSER;
                } else {
                    runnerUp = ref(loser, teamNames);
                    finalDisputed = chainGames != null && finalDisputed(league, fin, byRoster, chainGames);
                }
            }
        }

        // regular-season points leader(s): every roster tied at the max points_for
        List<PointsLeader> leaders = new ArrayList<>();
        Double max = null;
        for (StandingRow s : standings) {
            if (s.pointsFor() != null && (max == null || s.pointsFor() > max)) max = s.pointsFor();
        }
        if (max != null) {
            for (StandingRow s : standings) {
                if (s.pointsFor() != null && s.pointsFor().doubleValue() == max.doubleValue()) {
                    ManagerRef r = ref(s, teamNames);
                    leaders.add(new PointsLeader(r.managerId(), r.name(), r.teamName(), r.avatarId(), r.rosterId(), max));
                }
            }
        }

        // last place: via the refusing rule
        Set<Integer> nonPlayoff = null;
        if (!winnersBracket.isEmpty()) {
            nonPlayoff = new HashSet<>(byRoster.keySet());
            for (LeagueBracketRepository.Match m : winnersBracket) {
                for (Integer r : new Integer[]{m.t1RosterId(), m.t2RosterId(), m.winnerRosterId(), m.loserRosterId()}) {
                    if (r != null) nonPlayoff.remove(r);
                }
            }
        }
        Map<String, Double> points = new HashMap<>();
        for (RosterWeekPointsRepository.WeekPoint p : weekPoints.through(league.id(), Integer.MAX_VALUE)) {
            points.put(p.week() + ":" + p.rosterId(), p.startersPoints());
        }
        // S3: who actually shared a matchup id with whom, per week (the same stored pairing the rivalries use).
        Map<String, Integer> matchupOf = new HashMap<>();
        for (var f : fixtures.between(league.id(), league.season(), 1, 99)) {
            matchupOf.put(f.week() + ":" + f.rosterId(), f.matchupId());
        }
        LastPlaceRule.Result last = LastPlaceRule.evaluate(losersBracket, leagues.playoffFormat(league.id()).orElse(null),
                league.totalRosters(), nonPlayoff, (week, roster) -> points.get(week + ":" + roster),
                (week, a, b) -> {
                    Integer ma = matchupOf.get(week + ":" + a), mb = matchupOf.get(week + ":" + b);
                    return ma != null && ma.equals(mb);
                });
        ManagerRef lastPlace = null;
        String lastPlaceReason = last.reason();
        if (last.rosterId() != null) {
            StandingRow row = byRoster.get(last.rosterId());
            if (row == null) lastPlaceReason = LastPlaceRule.UNCOVERED_ROSTERS;
            else lastPlace = ref(row, teamNames);
        }

        return new TrophySeason(league.season(), league.sleeperId(), champion, runnerUp, runnerUpReason, finalDisputed,
                leaders, lastPlace, lastPlaceReason, last.bracket());
    }

    /**
     * Plan A7: read from the {@code pairCalc} of the bracket final's two managers, that season -- the same
     * {@code scoresAgreeWithWinner} the Rivalries page prints, so there is no third copy of the check.
     */
    private boolean finalDisputed(LeagueRow league, LeagueBracketRepository.Match fin, Map<Integer, StandingRow> byRoster,
                                  ChainGames cg) {
        StandingRow w = byRoster.get(fin.winnerRosterId());
        StandingRow l = byRoster.get(fin.loserRosterId());
        if (w == null || l == null || w.managerId() == null || l.managerId() == null
                || w.managerId().equals(l.managerId())) return false;
        PairKey key = PairKey.of(w.managerId(), l.managerId());
        PairCalc pc = rivalries.pairCalc(key, cg.byPair().getOrDefault(key, List.of()), null,
                cg.managersOfScoredLeague(), cg.formatByLeague());
        for (Final f : pc.record().finals()) {
            if (f.season() == league.season() && Boolean.FALSE.equals(f.scoresAgreeWithWinner())) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- nemesis and victim

    /**
     * Each chain the manager played in, with their nemesis and victim (research R9, plan A4).
     *
     * <p><b>Scope.</b> {@code visibleLeagueIds} is the caller's league set ({@code LeagueMembership.leagueIdsFor});
     * null means an operator, who sees every league. A manager's chain whose seasons are all out of scope is
     * omitted, and the chain is walked BACKWARD only from the manager's newest visible season (the walk careers
     * and membership use), so a caller who shares one league with the manager never learns about their others.
     *
     * <p><b>Orientation.</b> A pair is stored with the lower manager id as A; the record is turned around so wins
     * and losses are always the manager's own, whichever side they are on.
     */
    public Optional<ManagerRivals> rivals(long managerId, Set<Long> visibleLeagueIds) {
        List<StandingRow> all = rosterSeasons.forManager(managerId);
        if (all.isEmpty()) return Optional.empty();   // no such manager (contracts C3: 404)
        List<StandingRow> mine = new ArrayList<>();
        for (StandingRow s : all) {
            if (visibleLeagueIds == null || visibleLeagueIds.contains(s.leagueId())) mine.add(s);
        }
        // forManager is newest season first, so the first season met in a chain is the manager's newest visible one.
        Set<String> covered = new HashSet<>();
        Map<String, List<ChainRivals>> bySport = new LinkedHashMap<>();
        for (StandingRow s : mine) {
            if (covered.contains(s.sleeperLeagueId())) continue;
            List<LeagueRow> chain = leagues.chainBySleeperId(s.sleeperLeagueId());
            if (chain.isEmpty()) continue;
            for (LeagueRow l : chain) covered.add(l.sleeperId());
            bySport.computeIfAbsent(s.sport().code(), k -> new ArrayList<>()).add(chainRivals(managerId, chain));
        }
        List<ChainRivals> chains = new ArrayList<>();
        for (List<ChainRivals> perSport : bySport.values()) chains.addAll(perSport);
        return Optional.of(new ManagerRivals(managerId, chains));
    }

    private ChainRivals chainRivals(long managerId, List<LeagueRow> chain) {
        LeagueRow head = chain.get(0);
        Integer n = props.minMeetings();
        if (n == null) {
            return new ChainRivals(head.sport().code(), head.name(), head.sleeperId(), "NONE_QUALIFY", null,
                    List.of(), List.of());
        }
        ChainGames cg = rivalries.chainGames(chain);

        // Who each opponent is: their roster in the NEWEST chain season they played (the head season if they are
        // still there), with that season's team name.
        Map<Long, StandingRow> rosterOf = new HashMap<>();
        Map<Long, LeagueRow> leagueOfRoster = new HashMap<>();
        Set<Long> inHead = new HashSet<>();
        for (LeagueRow l : chain) {
            for (StandingRow r : rosterSeasons.forLeague(l.id())) {
                if (r.managerId() == null) continue;
                if (rosterOf.putIfAbsent(r.managerId(), r) == null) leagueOfRoster.put(r.managerId(), l);
                if (l.id() == head.id()) inHead.add(r.managerId());
            }
        }
        Map<Long, Map<Long, String>> teamNames = new HashMap<>();

        List<RivalEntry> all = new ArrayList<>();
        for (Map.Entry<PairKey, List<RivalryService.Game>> e : cg.byPair().entrySet()) {
            PairKey key = e.getKey();
            boolean isLo = key.lo() == managerId;
            if (!isLo && key.hi() != managerId) continue;
            long opponentId = isLo ? key.hi() : key.lo();
            PairCalc pc = rivalries.pairCalc(key, e.getValue(), null, cg.managersOfScoredLeague(), cg.formatByLeague());
            if (!"MET".equals(pc.record().status())) continue;
            StandingRow oppRow = rosterOf.get(opponentId);
            if (oppRow == null) continue;   // an opponent with no roster in the chain can't be named
            LeagueRow oppLeague = leagueOfRoster.get(opponentId);
            String team = teamNames.computeIfAbsent(oppLeague.id(), members::teamNamesByManager).get(opponentId);
            ManagerRef opp = new ManagerRef(opponentId,
                    oppRow.managerName() == null || oppRow.managerName().isBlank()
                            ? "Manager " + opponentId : oppRow.managerName(),
                    team, oppRow.avatarId(), oppRow.rosterId());
            all.add(orient(managerId, pc.record(), opp, !inHead.contains(opponentId)));
        }
        return summarize(head, n, all);
    }

    /**
     * A pair is stored with the lower manager id as A. Turn it around so wins and losses are the MANAGER's:
     * when the manager is the higher id (side B) their wins are the pair's {@code bWins}.
     */
    static RivalEntry orient(long managerId, RivalryService.PairRecord r, ManagerRef opponent, boolean former) {
        boolean managerIsA = r.aManagerId() == managerId;
        return new RivalEntry(opponent, r.meetings(), managerIsA ? r.aWins() : r.bWins(),
                managerIsA ? r.bWins() : r.aWins(), r.ties(), former);
    }

    /**
     * Pure ranking (unit-testable with synthetic entries): an opponent qualifies at {@code >= n} meetings, a
     * nemesis needs a strictly losing record and a victim a strictly winning one.
     */
    static ChainRivals summarize(LeagueRow head, int n, List<RivalEntry> all) {
        List<RivalEntry> qualifying = all.stream().filter(x -> x.meetings() >= n).toList();
        List<RivalEntry> nemesis = extreme(qualifying.stream().filter(x -> x.losses() > x.wins()).toList(), true);
        List<RivalEntry> victim = extreme(qualifying.stream().filter(x -> x.wins() > x.losses()).toList(), false);
        return new ChainRivals(head.sport().code(), head.name(), head.sleeperId(),
                qualifying.isEmpty() ? "NONE_QUALIFY" : "OK", n, nemesis, victim);
    }

    /** The manager's win share against this opponent (a tie is half), the one number both lists rank on. */
    private static double winShare(RivalEntry x) {
        return (x.wins() + x.ties() / 2.0) / x.meetings();
    }

    /**
     * The entries at the extreme win share, then (among those) the most meetings; every tie is kept, ordered by
     * opponent id. {@code lowest}: nemesis (lowest share); otherwise victim (highest share).
     */
    private static List<RivalEntry> extreme(List<RivalEntry> candidates, boolean lowest) {
        if (candidates.isEmpty()) return List.of();
        double best = lowest
                ? candidates.stream().mapToDouble(HallOfFameService::winShare).min().getAsDouble()
                : candidates.stream().mapToDouble(HallOfFameService::winShare).max().getAsDouble();
        List<RivalEntry> atBest = candidates.stream().filter(x -> winShare(x) == best).toList();
        int mostMeetings = atBest.stream().mapToInt(RivalEntry::meetings).max().getAsInt();
        return atBest.stream().filter(x -> x.meetings() == mostMeetings)
                .sorted(Comparator.comparingLong((RivalEntry x) -> x.opponent().managerId()))
                .toList();
    }

    private static ManagerRef ref(StandingRow s, Map<Long, String> teamNames) {
        String name = s.managerName() == null || s.managerName().isBlank()
                ? (s.managerId() == null ? "Roster " + s.rosterId() : "Manager " + s.managerId())
                : s.managerName();
        String team = s.managerId() == null ? null : teamNames.get(s.managerId());
        return new ManagerRef(s.managerId(), name, team, s.avatarId(), s.rosterId());
    }

    private static <T> Map<Long, List<T>> group(List<T> rows, java.util.function.ToLongFunction<T> leagueOf) {
        Map<Long, List<T>> out = new HashMap<>();
        for (T r : rows) out.computeIfAbsent(leagueOf.applyAsLong(r), k -> new ArrayList<>()).add(r);
        return out;
    }
}
