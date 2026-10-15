package com.ballknowers.draftsim.ingest.espn;

import com.ballknowers.draftsim.domain.Sport;

import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * An ESPN league a user pasted: its game ({@code ffl} / {@code fba}), id, and the season to start
 * from. Parsed and validated before any network call; malformed input is rejected with a fixed
 * {@code MALFORMED_REF} message that never echoes the input.
 *
 * <p>ESPN's league id is stable across seasons and shared across games (ffl 12345 is not fba 12345),
 * so the app's key is {@code espn-{game}-{leagueId}-{season}} (research R14).
 *
 * @param season the APP season (see {@link EspnSeasons}); ESPN's own id is {@link #espnSeason()}
 * @param teamId the {@code teamId=} of a pasted team link, if any; a hint for the claim step only
 */
public record EspnLeagueRef(String game, Sport sport, String leagueId, int season, Integer teamId) {

    private static final Pattern DIGITS = Pattern.compile("\\d{1,12}");
    private static final Pattern SEASON = Pattern.compile("(19|20)\\d{2}");

    public static final String MALFORMED_REF = "MALFORMED_REF";

    public EspnLeagueRef {
        if (!DIGITS.matcher(leagueId == null ? "" : leagueId).matches() || season < 1900 || season > 2100) {
            throw new IllegalArgumentException(MALFORMED_REF);
        }
    }

    /** ESPN's game code for a sport. */
    public static String gameOf(Sport sport) {
        return sport == Sport.NFL ? "ffl" : "fba";
    }

    public static EspnLeagueRef of(String leagueId, Sport sport, int season) {
        return new EspnLeagueRef(gameOf(sport), sport, leagueId, season, null);
    }

    /**
     * Accepts {@code fantasy.espn.com/{football|basketball}/(league|team|league/standings|...)?leagueId=N
     * [&seasonId=Y][&teamId=K]}, with or without a scheme.
     *
     * @param defaultSeason an APP season, used when the link has no {@code seasonId}; the caller's notion
     *                      of the current season for that sport, never a hard-coded year here
     */
    public static EspnLeagueRef parse(String link, int defaultSeason) {
        if (link == null || link.isBlank() || link.length() > 600) throw new IllegalArgumentException(MALFORMED_REF);
        String t = link.trim();
        if (!t.contains("://")) t = "https://" + t;
        URI uri;
        try {
            uri = URI.create(t);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(MALFORMED_REF);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!host.equals("fantasy.espn.com") && !host.equals("www.espn.com") && !host.equals("espn.com")) {
            throw new IllegalArgumentException(MALFORMED_REF);
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        String[] segments = path.split("/");
        String first = null;
        for (String s : segments) {
            if (!s.isEmpty()) {
                first = s.toLowerCase(Locale.ROOT);
                break;
            }
        }
        Sport sport;
        if ("football".equals(first)) sport = Sport.NFL;
        else if ("basketball".equals(first)) sport = Sport.NBA;
        else throw new IllegalArgumentException(MALFORMED_REF);

        Map<String, String> q = queryOf(uri.getRawQuery());
        String leagueId = q.get("leagueId");
        if (leagueId == null) throw new IllegalArgumentException(MALFORMED_REF);
        int season = defaultSeason;
        String sid = q.get("seasonId");
        if (sid != null) {
            if (!SEASON.matcher(sid).matches()) throw new IllegalArgumentException(MALFORMED_REF);
            season = EspnSeasons.toApp(sport, Integer.parseInt(sid));
        }
        Integer teamId = null;
        String tid = q.get("teamId");
        if (tid != null) {
            if (!DIGITS.matcher(tid).matches()) throw new IllegalArgumentException(MALFORMED_REF);
            teamId = Integer.parseInt(tid);
        }
        return new EspnLeagueRef(gameOf(sport), sport, leagueId, season, teamId);
    }

    private static Map<String, String> queryOf(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            out.putIfAbsent(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return out;
    }

    /** ESPN's seasonId for this ref's app season. */
    public int espnSeason() {
        return EspnSeasons.toEspn(sport, season);
    }

    /** {@code espn-{game}-{leagueId}-{season}}, this season's {@code league.sleeper_id}. */
    public String key() {
        return keyFor(season);
    }

    public String keyFor(int s) {
        return "espn-" + game + "-" + leagueId + "-" + s;
    }

    public EspnLeagueRef withSeason(int s) {
        return new EspnLeagueRef(game, sport, leagueId, s, teamId);
    }
}
