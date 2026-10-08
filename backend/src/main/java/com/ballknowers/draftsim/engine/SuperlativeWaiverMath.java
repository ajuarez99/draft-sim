package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.store.*;
import java.util.*;
import java.util.function.Predicate;
import static com.ballknowers.draftsim.util.Rounding.round2;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.*;

import static com.ballknowers.draftsim.engine.SeasonSuperlativesService.*;

/**
 * WAIVER_WIRE_WARRIOR and JABARI_SMITH_JR (most added): pure arithmetic over rows the
 * service has already read.
 *
 * <p>Moved out of {@link SeasonSuperlativesService} by specs/021-codebase-cleanup (T051)
 * as a pure move, proven by live JSON parity against the pre-split build.
 */
final class SuperlativeWaiverMath {

    private SuperlativeWaiverMath() {}

    /**
     * The "pick winners from the per-roster map" step of {@link #waiverSuperlative}, extracted so the
     * empty-state rule below is testable without Postgres (spec 010 T010).
     */
    static Superlative waiverWinners(Map<Integer, WaiverPickupAttribution.RosterTotal> byRoster, Coverage coverage,
                                     boolean early, Set<Integer> rosterIds, Map<String, Player> playersBySleeperId,
                                     Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster,
                                     Map<Integer, Long> managerByRoster) {
        if (byRoster.isEmpty()) {
            return new Superlative(Kind.WAIVER_WIRE_WARRIOR, true, null, early, null, "POINTS", List.of(),
                    "no starting-lineup points credited to a waiver or free-agent pickup yet", List.of(), coverage);
        }

        double max = byRoster.values().stream()
                .mapToDouble(WaiverPickupAttribution.RosterTotal::totalPoints).max().orElseThrow();
        // Plan amendment 9 (R4): the standings count an absent roster as a real 0, so a winner at or
        // below 0 would sit level with, or behind, teams that did nothing. That is no winner at all.
        if (max <= 0) {
            return new Superlative(Kind.WAIVER_WIRE_WARRIOR, true, null, early, null, "POINTS", List.of(),
                    "no started pickup has scored yet", List.of(), coverage);
        }
        List<Integer> topRosters = byRoster.entrySet().stream()
                .filter(e -> Double.compare(e.getValue().totalPoints(), max) == 0)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        List<Holder> holders = topRosters.stream()
                .map(id -> holder(id, nameByRoster, avatarByRoster, managerByRoster))
                .toList();

        List<DetailRow> detail = pickupDetail(topRosters, byRoster, playersBySleeperId);

        return new Superlative(Kind.WAIVER_WIRE_WARRIOR, true, null, early, round2(max), "POINTS", holders, null,
                detail, coverage, List.of(),
                waiverStandings(byRoster, rosterIds, nameByRoster, avatarByRoster, managerByRoster), List.of());
    }

    /**
     * Top-3-per-holder PICKUP rows. Each row carries the rosterId of the
     * holder it was built for -- not derived from the player, since a tie
     * between two holders means the SAME player could in principle appear
     * once per holder's own detail list, each tagged with its own roster
     * (coordinator follow-up after the live check: every other detail type
     * already carries rosterId, PICKUP was the one that didn't). Package-
     * private so SeasonSuperlativesWaiverTest can call it directly, without
     * Postgres.
     */
    static List<DetailRow> pickupDetail(List<Integer> topRosters,
                                        Map<Integer, WaiverPickupAttribution.RosterTotal> byRoster,
                                        Map<String, Player> playersBySleeperId) {
        List<DetailRow> detail = new ArrayList<>();
        for (int id : topRosters) {
            for (WaiverPickupAttribution.PlayerContribution pc : byRoster.get(id).contributions().stream().limit(3).toList()) {
                Player p = playersBySleeperId.get(pc.playerId());
                detail.add(new PickupDetail(pc.playerId(), p == null ? "Unknown player" : p.name(),
                        p == null ? null : p.primary().name(), id, pc.addedWeek(), pc.addType(), pc.startedWeeks(),
                        pc.points()));
            }
        }
        return detail;
    }

    /**
     * JABARI_SMITH_JR (T076), per research R16: the player picked up the most times off waivers
     * or free agency, across the covered weeks. The rule itself is {@link MostAddedPlayers#rank}
     * -- pure and tested without Postgres (T074); this method's own job is gathering that pure
     * function's inputs from the already-fetched transaction rows (research R16 decision 4: one
     * "completed pickup" parse, shared with {@link #waiverSuperlative}) and shaping its output
     * into ADD detail rows.
     *
     * <p>Not early-eligible (research R16 decision 5): a plain count of events, like CLOSE_WINS,
     * not a model or season total that gets noisier the fewer weeks there are.
     */
    static Superlative mostAddedSuperlative(String sleeperLeagueId, List<LeagueTransactionRepository.Row> txRows,
                                             int throughWeek, Map<String, Player> playersBySleeperId,
                                             Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster) {
        if (txRows.isEmpty()) {
            return unavailable(Kind.JABARI_SMITH_JR,
                    "Transactions for this season haven't loaded yet.");
        }

        List<WaiverPickupAttribution.CompletedAdd> completedAdds = WaiverPickupAttribution.completedAdds(txRows);

        // research R16 decision 2: a player whose stored positions include DEF is a football
        // team defense, never an eligible "player". An unknown id (not in `player`) stays
        // eligible and is named "Unknown player" below -- no sport comparison here, since
        // Position.fromSleeper already mapped positions per sport at ingest.
        Predicate<String> eligible = playerId -> {
            Player p = playersBySleeperId.get(playerId);
            return p == null || !p.positions().contains(Position.DEF);
        };

        List<MostAddedPlayers.Ranked> ranked = MostAddedPlayers.rank(completedAdds, throughWeek, eligible);

        if (ranked.isEmpty()) {
            return new Superlative(Kind.JABARI_SMITH_JR, true, null, false, null, "ADDS", List.of(),
                    "nobody's been picked up yet", List.of(), null, List.of());
        }
        // Spec clarification 18: a top of 1 add is an every-pickup tie (25 players
        // on NFL 2026 through week 2), not a most-added player. Say so instead.
        if (ranked.get(0).adds() < MostAddedPlayers.MIN_ADDS_TO_NAME) {
            return new Superlative(Kind.JABARI_SMITH_JR, true, null, false, null, "ADDS", List.of(),
                    "nobody's been picked up twice yet", List.of(), null, List.of());
        }

        List<PlayerHolder> playerHolders = new ArrayList<>();
        List<DetailRow> detail = new ArrayList<>();
        for (MostAddedPlayers.Ranked r : ranked) {
            Player p = playersBySleeperId.get(r.playerId());
            playerHolders.add(new PlayerHolder(r.playerId(), p == null ? "Unknown player" : p.name(),
                    p == null ? null : p.primary().name(), p == null ? null : p.team(), r.adds(), r.distinctTeams()));
            for (WaiverPickupAttribution.CompletedAdd a : r.counted()) {
                detail.add(new AddDetail(r.playerId(), a.week(), a.rosterId(),
                        nameByRoster.getOrDefault(a.rosterId(), "Roster " + a.rosterId()),
                        avatarByRoster.get(a.rosterId()), a.type(), a.faabBid()));
            }
        }

        // Spec 010: the full player standings, top 10 plus anyone tied with the 10th. MostAddedPlayers.top
        // shares rank()'s definition of which adds count, so its top tier is exactly the playerHolders
        // above. `standings` stays [] -- this award ranks players, not teams.
        List<PlayerStanding> playerStandings = new ArrayList<>();
        int rank = 0;
        int prevAdds = -1;
        List<MostAddedPlayers.Ranked> top = MostAddedPlayers.top(completedAdds, throughWeek, eligible, 10);
        for (int i = 0; i < top.size(); i++) {
            MostAddedPlayers.Ranked r = top.get(i);
            if (r.adds() != prevAdds) rank = i + 1; // competition ranks, like every other standings list
            prevAdds = r.adds();
            Player p = playersBySleeperId.get(r.playerId());
            playerStandings.add(new PlayerStanding(rank, r.playerId(), p == null ? "Unknown player" : p.name(),
                    p == null ? null : p.primary().name(), p == null ? null : p.team(), r.adds(), r.distinctTeams()));
        }

        return new Superlative(Kind.JABARI_SMITH_JR, true, null, false, (double) ranked.get(0).adds(), "ADDS",
                List.of(), null, detail, null, playerHolders, List.of(), playerStandings);
    }
}
