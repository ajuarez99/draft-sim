package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Which league-SEASON a season-scoped page should actually show.
 *
 * <p>The rail links league pages at {@code lineage.current} -- the newest
 * season in the chain -- because History and Power Rankings walk the whole
 * chain themselves and so do not care which year's id they are handed. The
 * pages added in specs/004-ffwrapped-feature-parity are not chain-walkers:
 * each answers about one season. Handed the current season they answer about
 * the current season, which for a league whose new year has been created but
 * not played is an honest "nothing scored yet" in response to a click the
 * reader meant as "show me this league".
 *
 * <p>Measured on the real NBA league: the rail points at 2026 (created, zero
 * scored weeks) while 2025 sits finished behind it with 21. Every one of the
 * four new pages refused, correctly and uselessly.
 *
 * <p>So: walk back to the newest season that has actually been played, and
 * tell the caller when that is not the season it asked for. The page names the
 * season either way, and says so explicitly when it moved -- a silently
 * different year would be worse than the refusal it replaces.
 */
@Service
public class LeagueSeasonResolver {

    private final LeagueRepository leagues;
    private final RosterWeekPointsRepository weekPoints;

    private final SeasonBoxCache boxCache;

    public LeagueSeasonResolver(LeagueRepository leagues, RosterWeekPointsRepository weekPoints,
                                SeasonBoxCache boxCache) {
        this.leagues = leagues;
        this.weekPoints = weekPoints;
        this.boxCache = boxCache;
    }

    /**
     * What "has been played" means. A required argument everywhere, never defaulted: the two rules
     * disagree on opening night, when a season has stored games but no scored fantasy week yet.
     */
    public enum Rule {
        /** The season has at least one stored scored week ({@code roster_week_points}). */
        PLAYED_WEEKS,
        /** The season has at least one stored {@code player_game} row (spec 022 R7). */
        STORED_GAMES
    }

    /** One season of a league's chain. {@code hasGames}: it has stored {@code player_game} rows. */
    public record SeasonOption(int season, String sleeperLeagueId, boolean hasGames) {}

    /**
     * @param league          the season to answer about
     * @param requestedSeason the season originally asked for, or null when it
     *                        is the one being answered about. Non-null is the
     *                        signal that a page should say it moved.
     */
    public record Resolved(LeagueRepository.LeagueRow league, Integer requestedSeason) {}

    /**
     * The newest played season in {@code sleeperLeagueId}'s chain.
     *
     * <p>Falls back to the requested season when no season in the chain has
     * been played: a league that has never scored a week should still refuse
     * with its own year rather than with an older league's.
     */
    public Optional<Resolved> resolve(String sleeperLeagueId) {
        return resolve(sleeperLeagueId, Rule.PLAYED_WEEKS);
    }

    /**
     * {@link #resolve(String)} under an explicit rule. {@code STORED_GAMES} walks back to the newest
     * season whose stored {@code player_game} count is above 0 (the cache's own token, N17); same
     * {@link Resolved} semantics and the same fall back to the requested season.
     */
    public Optional<Resolved> resolve(String sleeperLeagueId, Rule rule) {
        List<LeagueRepository.LeagueRow> chain = leagues.chainBySleeperId(sleeperLeagueId);
        if (chain.isEmpty()) return Optional.empty();

        LeagueRepository.LeagueRow requested = chain.getFirst();
        for (LeagueRepository.LeagueRow row : chain) {
            if (qualifies(row, rule)) {
                return Optional.of(new Resolved(
                        row, row.season() == requested.season() ? null : requested.season()));
            }
        }
        return Optional.of(new Resolved(requested, null));
    }

    private boolean qualifies(LeagueRepository.LeagueRow row, Rule rule) {
        return switch (rule) {
            case PLAYED_WEEKS -> !weekPoints.storedWeeks(row.id()).isEmpty();
            case STORED_GAMES -> hasGames(row);
        };
    }

    private boolean hasGames(LeagueRepository.LeagueRow row) {
        return boxCache.token(row.sport(), row.season()).count() > 0;
    }

    /**
     * Every season of {@code sleeperLeagueId}'s chain, newest first: walk forward to the chain head
     * with {@code successorOf} (cycle-guarded), then back with {@code chainBySleeperId}. Empty when
     * the id is unknown.
     */
    public List<SeasonOption> seasons(String sleeperLeagueId) {
        Optional<LeagueRepository.LeagueRow> start = leagues.bySleeperId(sleeperLeagueId);
        if (start.isEmpty()) return List.of();
        java.util.Set<String> seen = new java.util.HashSet<>();
        String head = sleeperLeagueId;
        seen.add(head);
        while (true) {
            Optional<LeagueRepository.LeagueRow> next = leagues.successorOf(head);
            if (next.isEmpty() || !seen.add(next.get().sleeperId())) break;
            head = next.get().sleeperId();
        }
        List<SeasonOption> out = new java.util.ArrayList<>();
        for (LeagueRepository.LeagueRow row : leagues.chainBySleeperId(head)) {
            out.add(new SeasonOption(row.season(), row.sleeperId(), hasGames(row)));
        }
        return out;
    }
}
