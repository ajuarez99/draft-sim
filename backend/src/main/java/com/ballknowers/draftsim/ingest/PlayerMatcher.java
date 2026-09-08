package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Matches an external ADP source's (name, position, team) against this
 * project's player table. One place, shared by every source — per
 * claude/adp-sources.md #7, every source disagrees with Sleeper about names,
 * and a matcher that gets it wrong silently corrupts the board rather than
 * failing loudly.
 *
 * Deliberately conservative: exact normalized (name, position) first, name
 * alone only when it is unambiguous, DEF on team abbreviation (the one field
 * every source agrees on for defenses), otherwise unmatched. Team is never a
 * required key for offensive players — a source snapshotted before a trade has
 * a stale team, and that is the most common way a matcher silently drops a
 * good player.
 */
public final class PlayerMatcher {

    private static final Pattern SUFFIX = Pattern.compile("\\s+(jr|sr|ii|iii|iv|v)\\.?$");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9 ]");
    private static final Pattern WS = Pattern.compile("\\s+");

    private final Map<String, Long> byNameAndPosition = new HashMap<>();
    private final Map<String, List<Long>> byNameOnly = new HashMap<>();
    private final Map<String, Long> byTeamForDefense = new HashMap<>();
    private final Map<String, Long> byAlias;
    private final Sport sport;

    private PlayerMatcher(Sport sport, Map<String, Long> alias) {
        this.sport = sport;
        this.byAlias = alias;
    }

    public static PlayerMatcher build(Sport sport, List<Player> players) {
        return build(sport, players, Map.of());
    }

    /**
     * @param sport the sport of the raw (name, position, team) rows {@link #match}
     *              will be asked about -- needed to parse {@code rawPosition} with
     *              the right {@link Position#fromSleeper(String, Sport)} table,
     *              since the same raw string means different things per sport.
     * @param alias normalized source name -> internal player id, hand-filled misses
     */
    public static PlayerMatcher build(Sport sport, List<Player> players, Map<String, Long> alias) {
        PlayerMatcher m = new PlayerMatcher(sport, alias);
        for (Player p : players) {
            String name = normalize(p.name());
            List<Position> positions = p.positions().isEmpty() ? List.of(p.primary()) : p.positions();
            for (Position pos : positions) {
                m.byNameAndPosition.putIfAbsent(name + "|" + pos.name(), p.id());
            }
            m.byNameOnly.computeIfAbsent(name, k -> new ArrayList<>()).add(p.id());
            // Football-only: DEF is an NFL-only Position (Position.DEF.sport() ==
            // Sport.NFL), and matching a defense by team abbreviation is
            // meaningless for basketball, which has no team-unit concept at all.
            // Guarded explicitly rather than relying on positions.contains(DEF)
            // alone staying accidentally false for nba callers.
            if (p.sport() == Sport.NFL && positions.contains(Position.DEF)
                    && p.team() != null && !p.team().isBlank()) {
                m.byTeamForDefense.put(p.team().toUpperCase(Locale.ROOT), p.id());
            }
        }
        return m;
    }

    /** Empty means unmatched. The caller is responsible for logging the miss. */
    public Optional<Long> match(String rawName, String rawPosition, String rawTeam) {
        Position pos = Position.fromSleeper(rawPosition, sport).orElse(null);

        if (pos == Position.DEF) {
            Long byTeam = rawTeam == null ? null : byTeamForDefense.get(rawTeam.toUpperCase(Locale.ROOT));
            if (byTeam != null) return Optional.of(byTeam);
        }

        String name = normalize(rawName);
        Long aliased = byAlias.get(name);
        if (aliased != null) return Optional.of(aliased);

        if (pos != null) {
            Long exact = byNameAndPosition.get(name + "|" + pos.name());
            if (exact != null) return Optional.of(exact);
        }

        List<Long> byName = byNameOnly.get(name);
        if (byName != null && byName.size() == 1) return Optional.of(byName.get(0));

        return Optional.empty();
    }

    static String normalize(String raw) {
        if (raw == null) return "";
        String s = Normalizer.normalize(raw, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        s = s.toLowerCase(Locale.ROOT).trim();
        s = SUFFIX.matcher(s).replaceAll("");
        s = NON_ALNUM.matcher(s).replaceAll(" ");
        s = WS.matcher(s).replaceAll(" ").trim();
        return s;
    }
}
