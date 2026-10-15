package com.ballknowers.draftsim.ingest.espn;

import com.ballknowers.draftsim.domain.Sport;

import java.util.List;
import java.util.Map;

/**
 * One translated ESPN league-season: everything {@code EspnLeagueIngestService} writes for it, still
 * keyed by ESPN ids (team ids, ESPN member ids, ESPN player ids). Player ids are turned into app keys
 * afterwards, once {@code EspnPlayerResolver} has matched them, so this stays a pure function of the
 * payload (spec 035 T026).
 *
 * <p>Nothing here is a credential and nothing here is read from the database.
 */
public record EspnSeason(
        String key, String platformLeagueId, Sport sport, int appSeason, String previousKey, String name,
        int totalRosters, String status, Map<String, Object> settings, Map<String, Object> scoring,
        List<String> rosterPositions, List<String> scoringUntranslated, List<String> unmodelledSlots,
        List<Member> members, List<Team> teams, List<Period> periods, Draft draft,
        boolean multiWeekPeriods, int currentMatchupPeriod, boolean complete,
        Map<Integer, Integer> txScoringPeriodByMatchupPeriod) {

    /** @param commissioner ESPN's league-manager flag; absent reads false (unverified field name). */
    public record Member(String espnId, String displayName, boolean commissioner) {}

    /** @param position ESPN's label ({@code QB}, {@code PG}, or an unmodelled one); null when ESPN gave none. */
    public record Entry(String playerId, int lineupSlotId, String name, String position, Integer proTeamId) {}

    /**
     * @param ownerMemberId the primary owner (plan F18), the manager the roster row is attributed to
     * @param coOwners other members listed in {@code owners[]}; each gets a league_member row (plan F18)
     * @param finalPlacement 1 for the champion of a COMPLETE season, otherwise null
     */
    public record Team(int id, String teamName, String ownerMemberId, List<String> coOwners, int wins, int losses, int ties,
                       double pointsFor, double pointsAgainst, Integer finalPlacement, List<Entry> entries) {}

    public record Side(int teamId, double points) {}

    /** {@code away} is null for a bye. {@code decided} is false while ESPN's winner is UNDECIDED. */
    public record Matchup(int id, Side home, Side away, boolean decided) {}

    /** {@code week} is the app week: the NFL scoring period, or the NBA matchup period (plan F9). */
    public record Period(int matchupPeriodId, int week, List<Matchup> matchups) {}

    /**
     * A roster move, still keyed by ESPN ids. {@code adds} / {@code drops} map an ESPN player id to the TEAM
     * (roster) id that receives / gives him, the Sleeper convention. {@code rosterId} is null for a trade.
     * {@code faabBid} is already null unless the league is FAAB and the move is a waiver claim.
     */
    public record Tx(String externalId, int week, String type, String status, Integer rosterId,
                     Map<String, Integer> adds, Map<String, Integer> drops, Integer faabBid,
                     java.time.Instant createdAt) {}

    /** What a transactions payload translated to, with what was left out and why (type strings only, never ids). */
    public record TxResult(List<Tx> transactions, int skipped, java.util.Set<String> skippedTypes) {}

    public record DraftPick(int overall, int round, int teamId, String playerId) {}

    /** {@code pickOrder} is team ids in draft-slot order (slot = index + 1); empty when ESPN gave none. */
    public record Draft(String status, String type, int rounds, int teams, List<Integer> pickOrder,
                        List<DraftPick> picks) {}
}
