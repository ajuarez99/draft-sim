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

    // Strict (spec 035) state. Populated only by buildStrict; build() leaves these empty, so the
    // existing matcher's behaviour is untouched (FR-013).
    private final Map<String, List<Long>> strictByNameAndPosition = new HashMap<>();
    private final Map<String, List<Long>> strictByTeamForDefense = new HashMap<>();
    private Map<Long, Long> espnIdByPlayerId = Map.of();
    /** Spec 046 C4: player id to upper-cased team, strict builds only; used by {@link #matchStrictWithTeam}. */
    private final Map<Long, String> teamByPlayerId = new HashMap<>();

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

    /** The result of {@link #matchStrict}. Never carries a guessed id. */
    public record Outcome(Kind kind, Long playerId) {

        public enum Kind { MATCHED, AMBIGUOUS, UNMATCHED, ESPN_ONLY_POSITION }

        static Outcome matched(long id) {
            return new Outcome(Kind.MATCHED, id);
        }

        static Outcome of(Kind kind) {
            return new Outcome(kind, null);
        }
    }

    /** {@link #buildStrict(Sport, List, Map, Map)} with no Sleeper espn_id hints. */
    public static PlayerMatcher buildStrict(Sport sport, List<Player> players, Map<String, Long> manual) {
        return buildStrict(sport, players, manual, Map.of());
    }

    /**
     * Spec 035's ambiguity-refusing build. {@link #build} keeps {@code putIfAbsent}, so when two
     * players share a normalized name and position the first one silently wins (research R3: Byron
     * Young would be guessed, not refused). Here a name+position key held by two or more players
     * resolves to AMBIGUOUS, never the first id.
     *
     * @param manual           normalized source name to internal player id, hand-filled misses
     * @param espnIdByPlayerId app player id to Sleeper's {@code espn_id}. Used ONLY to break a tie among
     *                         same-name candidates (plan F11): Sleeper's crosswalk matched 61/200 of the
     *                         most-owned ESPN NFL players and one match was a wrong person (Tyler Conklin
     *                         to Ryan Izzo), so it is never a key.
     */
    public static PlayerMatcher buildStrict(Sport sport, List<Player> players, Map<String, Long> manual,
                                            Map<Long, Long> espnIdByPlayerId) {
        PlayerMatcher m = new PlayerMatcher(sport, manual);
        m.espnIdByPlayerId = espnIdByPlayerId;
        for (Player p : players) {
            String name = normalize(p.name());
            List<Position> positions = p.positions().isEmpty() ? List.of(p.primary()) : p.positions();
            for (Position pos : positions) {
                List<Long> ids = m.strictByNameAndPosition.computeIfAbsent(name + "|" + pos.name(), k -> new ArrayList<>());
                if (!ids.contains(p.id())) ids.add(p.id());
            }
            m.byNameOnly.computeIfAbsent(name, k -> new ArrayList<>()).add(p.id());
            if (p.team() != null && !p.team().isBlank()) m.teamByPlayerId.put(p.id(), p.team().toUpperCase(Locale.ROOT));
            if (p.sport() == Sport.NFL && positions.contains(Position.DEF)
                    && p.team() != null && !p.team().isBlank()) {
                m.strictByTeamForDefense.computeIfAbsent(p.team().toUpperCase(Locale.ROOT), k -> new ArrayList<>()).add(p.id());
            }
        }
        return m;
    }

    /**
     * Resolves one ESPN player without ever guessing.
     *
     * @param rawPosition ESPN's position label for the player ({@code QB}, {@code CB}, {@code HC}, ...);
     *                    null or blank means ESPN gave none. A label this sport does not model (IDP, head
     *                    coach, team QB, punter) returns {@code ESPN_ONLY_POSITION} and is never matched by
     *                    name alone (plan F11): the name "Lamar Jackson" must not pull in the QB for a CB.
     * @param hint        Sleeper's espn_id for this ESPN player, or null. A tie-breaker only: it counts
     *                    when exactly one same-name candidate's espn_id equals it.
     */
    public Outcome matchStrict(String rawName, String rawPosition, String rawTeam, Long hint) {
        boolean positionGiven = rawPosition != null && !rawPosition.isBlank();
        Position pos = positionGiven ? Position.fromSleeper(rawPosition, sport).orElse(null) : null;
        if (positionGiven && pos == null) return Outcome.of(Outcome.Kind.ESPN_ONLY_POSITION);

        if (pos == Position.DEF) {
            // A defense is its team, never its name.
            List<Long> byTeam = rawTeam == null ? null : strictByTeamForDefense.get(rawTeam.toUpperCase(Locale.ROOT));
            return resolve(byTeam, hint);
        }

        String name = normalize(rawName);
        Long aliased = byAlias.get(name);
        if (aliased != null) return Outcome.matched(aliased);

        if (pos != null) {
            // ESPN gave a position (spec 035 code review S5): the name alone must NOT rescue a position
            // mismatch. An ESPN kicker "X" is not the app's quarterback "X"; a miss is better than a wrong person.
            List<Long> exact = strictByNameAndPosition.get(name + "|" + pos.name());
            return resolve(exact, hint);
        }
        // No position given at all: the name alone, through the same ambiguity rule.
        return resolve(byNameOnly.get(name), hint);
    }

    /**
     * Spec 046 C4: {@link #matchStrict} with the source's team as a tie-break among same-name candidates (Yahoo's
     * team is current, Sleeper's is current too; a stale or differently spelled team just leaves it AMBIGUOUS,
     * never a guess). Kept apart from {@link #matchStrict} so ESPN's spec-035 behaviour is untouched.
     */
    public Outcome matchStrictWithTeam(String rawName, String rawPosition, String rawTeam) {
        Outcome o = matchStrict(rawName, rawPosition, rawTeam, null);
        if (o.kind() != Outcome.Kind.AMBIGUOUS || rawTeam == null || rawTeam.isBlank()) return o;
        boolean positionGiven = rawPosition != null && !rawPosition.isBlank();
        Position pos = positionGiven ? Position.fromSleeper(rawPosition, sport).orElse(null) : null;
        String name = normalize(rawName);
        List<Long> candidates = pos != null ? strictByNameAndPosition.get(name + "|" + pos.name()) : byNameOnly.get(name);
        if (candidates == null) return o;
        String team = rawTeam.toUpperCase(Locale.ROOT);
        List<Long> sameTeam = candidates.stream().filter(id -> team.equals(teamByPlayerId.get(id))).toList();
        return sameTeam.size() == 1 ? Outcome.matched(sameTeam.get(0)) : o;
    }

    private Outcome resolve(List<Long> candidates, Long hint) {
        if (candidates == null || candidates.isEmpty()) return Outcome.of(Outcome.Kind.UNMATCHED);
        if (candidates.size() == 1) return Outcome.matched(candidates.get(0));
        if (hint != null) {
            Long only = null;
            int hits = 0;
            for (Long id : candidates) {
                if (hint.equals(espnIdByPlayerId.get(id))) {
                    only = id;
                    hits++;
                }
            }
            if (hits == 1) return Outcome.matched(only);
        }
        return Outcome.of(Outcome.Kind.AMBIGUOUS);
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
